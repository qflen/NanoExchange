# Engineering Decision Log

This document captures architectural decisions as they are made, using the lightweight ADR (Architecture Decision Record) format: **Context → Decision → Consequences**. New decisions are appended, not rewritten, so future readers can see how the design evolved.

---

## ADR-001: Fixed-point integer prices (price × 10⁸)

**Status:** Accepted — slice 1

**Context.** Prices must be represented with absolute precision (a price of "100.50" is not "approximately 100.50"). Floating-point types (`double`, `float`) introduce representation error — `0.1 + 0.2 != 0.3` is not a theoretical problem on a matching engine, it is a bug generator. Beyond correctness, `long` comparison is also faster and branch-predictable in a way FP comparison is not, and is trivially deterministic across CPUs and JVMs.

Real exchanges solve this with either (a) fixed-point integers, (b) `BigDecimal`, or (c) a custom decimal type. `BigDecimal` allocates on every arithmetic operation — non-starter for the hot path.

**Decision.** All prices are represented as a `long` holding the price scaled by 10⁸ (eight decimal places). A price of `100.50` is stored as `100_50000000`. The scale is fixed and implicit; it is documented in the wire protocol (slice 4) and in code comments next to the field declaration.

**Consequences.**
- Max representable price: ~9.22 × 10¹⁰ (well beyond any realistic instrument price).
- All arithmetic (compare, add, subtract) is a single machine instruction.
- Presentation-layer code (Python client, dashboard) must divide by 10⁸ to render. This is a one-liner and documented.
- Division — if ever needed in the engine — must be done carefully; for slice 1/2 the engine does not divide prices.

---

## ADR-002: Custom `LongHashMap` over `java.util.HashMap<Long, V>`

**Status:** Accepted — slice 1

**Context.** The engine uses a map keyed by order ID (`long`) for O(1) cancel/lookup. `java.util.HashMap<Long, V>` boxes the key on every `get`/`put`/`remove`, allocating a `Long` object per call. At even modest order rates this is a steady source of young-gen garbage, which translates directly to tail-latency spikes when GC runs.

Alternative options considered:
1. Use `HashMap<Long, V>` and accept the allocation — rejected, violates the zero-allocation-hot-path rule.
2. Use `Long2ObjectHashMap` from Agrona — a reasonable choice but pulls in a dependency, and part of the point of this project is to demonstrate the underlying technique, not to consume someone else's solution.
3. Build a custom open-addressing map with primitive `long` keys — chosen.

Design parameters:
- Linear probing vs double hashing vs Robin Hood — we chose **linear probing** for cache-friendly access (next slot is always adjacent in memory). Robin Hood is strictly better under adversarial keys but adds code complexity for a marginal win under the expected uniform-ish key distribution (monotonic order IDs hashed by Thomas Wang's mixer).
- Load factor — chose **0.5**. Linear probing's average probe length grows as roughly `1 / (1 - α)` where α is the load factor; at 0.5 the expected probe is 2, at 0.75 it is 4, at 0.9 it is 10. Tail-latency stability is worth more than halving memory.
- Key sentinel — chose **0 as the empty-slot marker**. Order IDs are assigned from a monotonic counter starting at 1, so 0 is never a valid key. Caught by `assert` in development; in release builds with assertions disabled there is no check (but no valid code path can insert 0).
- Tombstones — represented by a private sentinel stored in the values array, not a side-channel state table. Keeps key comparison to one machine comparison.

**Decision.** Ship `LongHashMap<V>` as described. `HashMap` is banned from the hot path.

**Consequences.**
- One more class to own and test; the random-ops test cross-checks against `HashMap` to catch divergence.
- Resize copies all entries, which is allowed to allocate (it's not on the hot path — it's a startup-sized concern). Pre-size the map at construction to avoid it entirely in practice.
- `get` return type is `V`, not `Optional<V>`. Callers check for null. This is a deliberate concession to hot-path performance.

---

## ADR-003: Object pooling via Vyukov MPMC ring buffer

**Status:** Accepted — slice 1

**Context.** The hot path must not allocate. `Order`, `ExecutionReport`, and similar short-lived message types are therefore pooled: pre-allocate N instances at startup, hand them out on acquire, reclaim them on release. This is the textbook technique for trading engines and JVM-based low-latency systems generally.

Two problems to solve:
1. **What is the data structure backing the pool?** A `ConcurrentLinkedQueue` allocates a `Node` per enqueue — defeats the purpose. A `LinkedBlockingQueue` uses locks — introduces blocking and unbounded tail latency. A simple array + index with `synchronized` — same problem. We need a **bounded, lock-free, allocation-free** queue.

2. **What concurrency regime does it support?** Within a fully single-threaded engine, the pool is effectively SPSC. But a realistic deployment has the network thread acquiring orders (to populate from the wire) and the engine thread releasing them back after processing — a producer/consumer split across threads. In a more aggressive setup multiple network threads might concurrently populate orders. MPMC covers all cases without forcing us to predict the final thread model.

**Decision.** Implement a Vyukov bounded MPMC queue (`ObjectPool<E>`). Each slot carries a sequence number that acts as a hand-off token between producers and consumers; operations are wait-free (bounded-step) rather than merely lock-free. Positions stored as `AtomicLong`; sequence array accessed via `VarHandle` with acquire/release semantics to establish the happens-before edge between the slot write (producer) and the slot read (consumer).

On empty, `acquire` returns `null` (not throws — exceptions on the hot path are unacceptable). On full, `release` returns `false` and the caller drops the reference; the lost instance is GC'd as a one-off cost, which is strictly preferable to blocking or failing the hot path.

**Consequences.**
- Pool construction pre-fills to capacity, so a warmed engine never allocates under steady-state traffic.
- Capacity is rounded up to a power of two — the ring index is a mask instead of a modulo.
- False sharing between the two position counters and between adjacent slots is not yet addressed. Padding is a known follow-up; deferred to slice 7 benchmarks, where we can measure whether it actually matters in practice before adding code for it.
- Callers must be disciplined: every acquired instance has a matching release. Leaked instances reduce effective capacity until restart. Unit tests cover round-trip and uniqueness; integration tests in later slices will verify long-running steady-state pool levels.

---

## ADR-004: Single-threaded matching engine

**Status:** Accepted — slice 2

**Context.** A matching engine has a global-state problem: every order affects a shared book. Correctness demands a total order over inbound operations — two orders at the same price/time must deterministically produce the same outcome every time. Multiple threads sharing a book need coordination, and every synchronization primitive you add — locks, STM, even lock-free structures — adds latency variance.

Real exchanges (NYSE Pillar, Nasdaq INET, LMAX, Bats, Eurex) all run matching on a single thread per instrument (or per partition of instruments), then scale horizontally by sharding. The thread-per-instrument approach buys:
- **Deterministic replay** — a journal of inbound messages plus a single-threaded consumer reproduces byte-for-byte the same output sequence, invaluable for debugging, backtesting, and regulatory audit.
- **No synchronization tax** — the hot path executes in the branch-predictor's sweet spot with no memory barriers.
- **Simpler reasoning** — invariants like "best bid < best ask" don't need atomic sections to hold.

The alternatives:
- **Lock-per-side** (split bid and ask across two threads): doesn't help because matching operations touch both sides.
- **STM / optimistic concurrency**: adds retry overhead and destroys latency predictability.
- **Partitioned books within one instrument**: impossible — price-time priority is defined across all orders for that instrument.

**Decision.** The matching engine runs on one thread. Inbound messages arrive via a lock-free ring buffer (slice 3), outbound execution reports go to a sink the calling thread owns. Horizontal scale comes from running one engine per instrument, not from parallelizing within one.

**Consequences.**
- Throughput of a single instrument is capped by one core's clock; headroom for ~10M msg/sec of straightforward limit-vs-limit matching on modern hardware is ample for realistic load.
- The engine must never block — no I/O, no locks, no allocation that might hit a collector pause. The journal (slice 3) is memory-mapped and the producer pattern is fire-and-forget from the engine's perspective.
- Cancel and modify API calls must be serialized through the same single thread, which means they flow through the same ring buffer as new orders.

---

## ADR-005: Sorted array (not red-black tree) for price levels

**Status:** Accepted — slice 2 (revisit in slice 7 with benchmarks)

**Context.** Each side of the book has a collection of price levels. The operations are: look up the best (most common — every match begins here), find a specific price (for insert/cancel), iterate from best toward worse (for depth queries and matching). A classical data-structures answer would be a balanced BST — O(log N) everything, tidy asymptotics.

But asymptotics lie on small N. A healthy book has a tight cluster of active levels near the BBO; a liquid stock might have 50 levels on each side and a quiet one might have 10. Pointer-chasing a red-black tree with that N hits cache misses the sorted-array version avoids entirely.

Sorted array tradeoff:
- Best-level access: O(1) (index 0).
- Specific-price lookup: O(log N) via binary search.
- Insert/remove at arbitrary position: O(N) due to shift.
- All of the above operate on contiguous memory, prefetcher-friendly.

RB-tree tradeoff:
- Insert/remove: O(log N) rotations, but each rotation touches non-adjacent memory.
- Iteration: O(N) with pointer chasing.

The question is whether the O(N) insert cost of the array ever actually bites. For a book with 50 levels, shifting 50 pointers is ~100ns worst case and usually far less (we typically insert near one end or at an existing level). The same 50-element RB-tree operation pays cache misses on at least log₂(50) ≈ 6 nodes.

**Decision.** Use a best-first sorted `PriceLevel[]` per side. Binary-search for insert/find, array-shift on mutation, power-of-two resize on growth. The level object itself is pooled (via `ObjectPool<PriceLevel>`) so creating a new level is allocation-free in steady state.

**Consequences.**
- The assumption "N is small" is load-bearing. Slice 7 benchmarks must validate it — if N grows past ~200 we revisit.
- The shift cost scales with N, so a pathological scenario (many fleeting levels at random prices) would punish this choice. Real market data does not look like that, but the test suite should include a worst-case insertion stress.
- Binary search in a 50-entry array is a pragmatic optimization; a linear scan from index 0 might actually win on branch prediction for very small N, but we keep the binary search for clarity and to handle sudden growth.

---

## ADR-006: Self-trade prevention policy — reject the incoming order

**Status:** Accepted — slice 2

**Context.** Trading against yourself is generally prohibited by exchange rules and is flagged by regulators as potential wash-trading. When an incoming order would cross a resting order from the same account, the exchange must prevent the trade. There are three standard policies:

1. **Skip** — ignore the same-account resting, match with whatever is behind it. Preserves the rest of the book untouched but can produce fills at prices worse than the best visible, which is a surprising execution outcome.
2. **Decrement-older** — cancel or reduce the resting order, then match the incoming against the remainder of the book. Modifies existing book state based on a new event, which is hard to reason about for users monitoring their resting orders.
3. **Reject-new** — if the incoming would cross any same-account resting at a crossing price, reject the incoming entirely. No fills, no state change to existing orders.

**Decision.** Reject-new. The engine performs an STP preflight scan before any matching; if any same-`clientId` resting exists at a crossing price, the incoming order is rejected with `REJECT_SELF_TRADE` and nothing else happens. No ACK, no partial fills, no silent state mutation.

**Consequences.**
- Simpler to reason about, simpler to explain to users. Every inbound event has one of two outcomes: normal processing, or a clean REJECTED-with-reason.
- FOK's own pre-match viability check only counts non-same-client display quantity, because even without STP-reject, same-client restings can't contribute to a fill.
- The preflight is a linear scan across crossing levels. In the common case (a shallow book with no self-conflict), it terminates on the first non-crossing level. Cost is bounded by book depth near BBO and is not a hot-path concern.
- This policy is easy to toggle later if a real venue prefers decrement-older; the preflight hook would move from a reject to a mutate step.

---

## ADR-007: Iceberg refill moves the order to the tail of the queue

**Status:** Accepted — slice 2

**Context.** An iceberg order has a visible "tip" quantity (`displayQuantity`) and a hidden reserve (`hiddenQuantity`). When the tip is fully matched, the order "refills" from the reserve. A policy question: does the refilled order retain its original time-priority position (i.e., remain at the head of its price level), or does it go to the tail?

Real exchanges differ in the details but the dominant convention — Nasdaq, Eurex, CME, most MTFs — is **refill-to-tail**. The reasoning: the whole point of an iceberg is to reduce visible market impact; in exchange for that privacy, you give up the aggressive time priority that a fully-displayed order would earn. If the refill kept priority, icebergs would be strictly better than displayed orders of equal size and the market would fill up with them, defeating the price-discovery function of the public quote.

**Decision.** On refill, the engine removes the iceberg from the book entirely, reassigns its `displayQuantity` from the hidden reserve (capped by `displaySize`), and re-appends it at the tail of its price level.

**Consequences.**
- Other resting orders that arrived after the iceberg but before its refill are now ahead in the queue. A subsequent taker sweeping the price level will fill them first.
- A pathological taker that repeatedly submits tiny orders to "walk" an iceberg down would still pay the iceberg's displayed price, but would not grant the iceberg disproportionate fill priority across its hidden quantity.
- The engine's match loop must handle the subtle case where a refilled order ends up at the tail of the same level it came from; the outer loop re-enters on the same best level and picks up the new head correctly.

---

## ADR-009: Manual cache-line padding on ring-buffer sequences

**Status:** Accepted — slice 3

**Context.** The SPSC ring buffer between the network thread (producer) and the engine thread (consumer) has two hot long fields: `producerSeq` and `consumerSeq`. The producer writes the former on every enqueue and reads the latter on potential wraparound; the consumer writes the latter on every dequeue and reads the former on potential empty. Logically the two sides never touch the same word — but if both fields live in the same 64-byte cache line (which they would, by default, being consecutive `long` fields on the same object), the CPU's coherence protocol treats every write as invalidating the other core's copy of the line. That coherence traffic shows up as MESI invalidations, additional L1 misses, and tens of nanoseconds of extra latency per enqueue and dequeue — exactly the kind of "spooky action at a distance" that gives busy SPSC queues their bad reputation on multi-core.

This is the classic **false sharing** problem. The cure is to ensure each of the two hot fields lives alone in its cache line.

**Options:**

1. **`@jdk.internal.vm.annotation.Contended`** — JDK-internal, requires `--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED` or `-XX:-RestrictContended`, not a portable story for a public library.
2. **Separate boxed objects per sequence** — destroys cache locality on the happy path and adds an indirection.
3. **Manual padding with unused `long` fields** — unambiguous, portable, zero runtime cost. Ugly but correct.

Option (3) is what LMAX, Aeron, Agrona, and JCTools all use for the same reason.

**Decision.** The `RingBuffer` class lays out its hot fields in four padded "islands," each spanning roughly one 64-byte cache line:

- Producer sequence island: 7 padding longs + `producerSeq` + 7 padding longs.
- Producer-local cached consumer sequence + 7 padding longs.
- Consumer sequence + 7 padding longs.
- Consumer-local cached producer sequence + 7 padding longs.

Seven longs on each side of the hot field is belt-and-suspenders: it guarantees the hot field occupies its own line regardless of object header alignment, JVM field-reordering choices, or the 128-byte "adjacent cache line prefetch" that some Intel chips perform (which is why the Aeron/Agrona convention is effectively 128 bytes, not 64).

Additionally, each side caches the opposite side's sequence in a producer-local / consumer-local field. On the happy path where the ring is neither full nor empty, the cached value is sufficient, and the authoritative opposite-side sequence is read only when the cache would indicate a block. That reduces cross-core reads by orders of magnitude under steady state.

**Consequences.**
- The `RingBuffer` class contains several `@SuppressWarnings("unused")` padding longs. These *must not* be removed by well-meaning refactoring; the compiler cannot detect their purpose. A comment next to each island explains why.
- Memory overhead is a few extra cache lines per ring — trivial compared to the slot array itself.
- The cached-sequence technique means a freshly-created ring observes the other side's sequence on the very first operation (since the cache is initialized to a "will block" value), which is correct; a steady-state ring only refreshes the cache on catch-up transitions, which is exactly what we want.
- Verification is structural: the test suite exercises the SPSC invariants (monotonic, no gaps, no duplicates) under load; false-sharing effects are visible in benchmarks (slice 7) rather than unit tests.
