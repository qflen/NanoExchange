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

## ADR-008: Memory-mapped append-only journal

**Status:** Accepted — slice 3

**Context.** Every engine input and every emitted execution report must persist so the engine can be replayed deterministically — for restart recovery, for regression testing against historical days, and for audit. The options:

1. **Log4j / SLF4J** text lines — simple but allocation-heavy, slow, and the format is not stable enough for byte-identical replay.
2. **Synchronous `FileChannel.write`** per record — every record is at least one syscall, which puts the engine one context switch away from a 10-microsecond tail excursion on every event. Batching helps, but the syscall still shows up in the p99.9.
3. **Memory-mapped file (`mmap`)** — the kernel handles the write through the page cache; from userspace a record "write" is a handful of `put`s into a `MappedByteBuffer`, which compiles down to ordinary store instructions. No syscall on the hot path. Flush (`msync` / `MappedByteBuffer.force`) is called periodically, not per record.

Option (3) is what LMAX, Aeron, Chronicle Queue, and most ultra-low-latency logging frameworks use, and for the same reason: it eliminates the syscall-per-record cost and turns persistence into a sequential-write workload that SSDs and page-cache-backed mechanisms handle very well.

**Decision.** The journal is a single memory-mapped file of a pre-allocated size (set at startup, typically tens to hundreds of megabytes — it's cheap virtual memory). Records are appended by writing into the mapped buffer at the current offset. `force()` is called only at batch/shutdown boundaries, not per record.

Record format is sequence (int64) + length (int32) + payload (N bytes) + CRC32 (int32), all little-endian. The CRC detects torn writes at the tail; a zero-length field detects untouched file space (the tail of the mapped region is zero-initialized). Replay stops cleanly on either signal, which means no explicit end-of-log marker is needed — the absence of a valid next record *is* the signal.

**Consequences.**
- Append latency is dominated by a few cache-line stores and one CRC computation; no syscall overhead. Measured in slice 3 as well under a microsecond per record on warm cache.
- The journal size is fixed at construction. If an engine session writes more than that, `append` throws `IllegalStateException`. Callers must size generously. Rolling segment files are a reasonable follow-up but add complexity that slice 3 doesn't need — one well-sized file per session is enough for benchmarks and test-day replay.
- `MappedByteBuffer` is not unmapped cleanly on JDK 21 without `sun.misc.Unsafe`-ish gymnastics. We live with this; the mapping is reclaimed when the JVM exits. Tests that open and close journals repeatedly use `@TempDir` so leftover mappings don't pollute the working tree.
- CRC32 is not cryptographic, which is fine — the goal is torn-write detection, not tamper detection. The implementation uses `java.util.zip.CRC32`, which is hardware-accelerated on x86-64 and ARM64 via intrinsics and costs only a few nanoseconds per small payload.

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

---

## ADR-010: Length-prefix framing over delimiter framing

**Status:** Accepted — slice 4

**Context.** The TCP byte stream carries variable-length messages. The two common ways to mark message boundaries are:

1. **Delimiter framing** — a reserved byte (or byte sequence) marks the end of each message. Requires either escaping inside payloads or guaranteeing the delimiter never appears in payload content.
2. **Length-prefix framing** — the first few bytes of each frame are a length field; the receiver reads exactly that many additional bytes.

Delimiter framing is common in text protocols (HTTP/1 uses CRLF, SMTP uses `.` lines) but binary payloads in NanoExchange include 64-bit prices, quantities, and timestamps — every byte value from `0x00` to `0xFF` is a legitimate payload byte. There is no delimiter that is not also a valid payload value, so delimiter framing would force payload escaping, which adds encode/decode complexity and makes the wire size non-constant for a given logical message.

**Decision.** All frames are prefixed with a 4-byte little-endian length field giving the number of bytes that follow. The body is then a 1-byte type + variable payload + 4-byte CRC32. The length field itself is not covered by the CRC; the CRC covers type + payload.

**Consequences.**
- Decoders have a tight state machine: wait for 4 bytes, read length, wait for length bytes, verify CRC, dispatch. No escape-character handling, no scanning for delimiters.
- A nonsensical length (negative, or larger than a 1 MiB sanity bound) is treated as a desynchronization and closes the connection. The sanity bound is generous relative to any legitimate message size (the biggest message is an execution report at ~75 bytes) but tight enough to catch runaway corruption quickly.
- The CRC is content-addressed (framing-independent). If the same message is carried inside a different envelope later — say, batched inside a UDP snapshot frame — the message's CRC is unchanged. This makes cross-transport validation tests easy.

---

## ADR-011: NIO Selector on a dedicated thread, zero engine work on the network thread

**Status:** Accepted — slice 4

**Context.** There are two reasonable Java networking primitives for a server at this scale:

1. **Blocking `SocketChannel` per client with a thread pool.** Simple but each thread costs ~1 MB of stack plus wakeup overhead. A few thousand clients become a scheduling problem.
2. **Non-blocking `SocketChannel` + `Selector` on one thread.** The kernel `kqueue`/`epoll` multiplexes thousands of connections; one user-space thread handles all ready events. This is the classical "reactor" pattern.

Option (2) is what almost every serious Java server uses — Netty, Vert.x, Undertow, LMAX Disruptor-based systems — and is a straightforward fit for an order gateway where per-connection work is tiny (parse a frame, forward to engine).

A subtler decision: what does the selector thread *do* with decoded events? Two wrong answers are (a) invoke the engine directly on the selector thread (blocks network I/O during engine work), and (b) allocate a queued-event object per message (defeats the zero-allocation discipline).

**Decision.** The order gateway runs the selector on a dedicated thread. That thread's job is narrow: accept connections, read bytes into a pre-allocated per-session buffer, decode frames, and forward the decoded fields into an `InboundHandler` callback. The handler is expected to push the decoded message into a lock-free ring buffer (slice 3) for the engine to consume. The return path uses another ring buffer: the engine publishes execution reports, and the selector thread reads them and writes to the appropriate client socket.

Both read and write buffers are `ByteBuffer.allocateDirect` at connection time and reused for the life of the connection. No allocation happens per frame in the steady state.

**Consequences.**
- Per-connection memory is bounded and known at startup.
- The selector thread never blocks on engine work — the worst it can do to order-entry latency is a dropped frame if the ring buffer to the engine is full (which we handle by backpressuring the client).
- Outbound writes can stall when a slow client causes socket-level backpressure. The gateway registers `OP_WRITE` in that case and resumes draining when the socket accepts more bytes. Pending outbound bytes are bounded by the per-session write buffer; overflow forces a disconnect, which is the correct policy for a slow client that can't keep up with the feed it subscribed to.
- The session-assigned `clientId` is authoritative. A client may include a clientId in its NEW_ORDER wire payload (useful for their own bookkeeping), but the gateway overwrites it with the session-bound value before forwarding — clients cannot spoof another account by putting that account's clientId in the frame. STP correctness depends on this.

---

## ADR-012: UDP multicast with snapshot + incremental for market data

**Status:** Accepted — slice 5

**Context.** Market data needs to fan out to many subscribers (humans, strategies, feed-replay tools) without the publisher maintaining a per-subscriber connection. Real exchanges solve this with UDP multicast: the publisher sends one datagram, the network replicates it to every subscriber who joined the group. This scales to hundreds of subscribers without affecting publisher CPU or state.

The downside of UDP is that it's lossy. A dropped packet is a real possibility over any non-trivial network, and a book state reconstructed from lossy incrementals will diverge from the exchange's view silently. Exchanges handle this with a periodic **snapshot**: a full-state datagram (or series of datagrams) stamped with the sequence number of the last update it reflects, so a consumer that missed packets can resync at a known boundary.

**Decision.** Market data is published over UDP multicast. The feed carries four message types: BOOK_UPDATE (aggregate level change), TRADE (a match), SNAPSHOT (periodic full book), and HEARTBEAT (liveness tick).

A single monotonic `feedSequence` is stamped onto every outbound datagram — across all message types, not per-type. Gaps in this sequence are how consumers detect dropped packets. Each SNAPSHOT carries the sequence of the last update it reflects (`startingSequence`), so a recovering consumer knows exactly where to resume applying incrementals.

Datagrams are sized to fit within the Ethernet MTU (1472 bytes). When a snapshot exceeds that (deep books), it splits across multiple parts using a `partNumber` / `totalParts` header.

**Consequences.**
- Subscribers scale out transparently. One publisher serves hundreds of consumers at the cost of a single multicast send per message.
- Consumers that lose packets recover deterministically by waiting for the next snapshot; no publisher-side state is involved.
- Snapshot frequency trades off recovery latency against bandwidth. A 1-second interval (default) gives recovery within ~1s while costing at most a few hundred KB of bandwidth per second for a liquid book.
- MTU awareness is a hard requirement; fragmentation in the IP layer would raise drop rates and hurt the whole feed. The builder enforces per-datagram size at encode time.
- Tests run on loopback (`239.200.3.x` with `IP_MULTICAST_LOOP` enabled). Production deployments must configure the multicast interface and TTL explicitly because macOS and Linux default differently — the publisher API forces the caller to supply a `NetworkInterface` rather than silently defaulting to whatever the kernel picks.

---

## ADR-013: Python client mirrors the Java wire protocol via `struct` format strings

**Status:** Accepted — slice 6

**Context.** The project has two independent encoder/decoder implementations of the same wire protocols: Java (`WireCodec`, `FeedCodec`) on the exchange side, Python (`protocol.py`, `feed.py`) on the client side. A drift between them — a field reordered, a padding byte hallucinated, a length miscounted — would only surface as hard-to-diagnose connectivity failures. The canonical protocol is documented in `docs/PROTOCOL.md` but a prose spec is not machine-checkable.

Two choices offered themselves for the Python side:

1. **Hand-rolled byte manipulation** (e.g. `int.from_bytes` per field). Explicit, but verbose and easy to desync from the Java layout by hand.
2. **`struct` format strings.** Declarative, one string per payload, and trivially eyeballable against the Java `ByteBuffer.putLong`/`put` sequence. Byte-for-byte deterministic because format strings explicitly suppress native alignment (`<` prefix).

**Decision.** Python payloads are encoded via `struct.Struct("<…")` format strings, one per message type, mirroring the Java layout. Format strings live at the top of `protocol.py` / `feed.py` next to the Java code they mirror, so a reader can eyeball the two layouts side by side.

Frame-level framing (length prefix for TCP, CRC for both transports) is still done in Python code rather than a format string, because the CRC covers a dynamic-length region.

Cross-language byte equality is **not** enforced per slice (would slow unit tests and confuse local development). Instead, slice 13 adds a dedicated consistency test that generates the same messages from both stacks and asserts the bytes are identical. Until then, the Java + Python unit tests share a spec (`docs/PROTOCOL.md`) as the source of truth.

**Consequences.**
- Adding a new wire message requires editing two files (Java + Python) plus `docs/PROTOCOL.md`. That is by design — the spec should be updated first, then the two implementations.
- A `struct` format string will not catch all drift on its own (e.g. if someone reorders fields consistently in both languages but the spec says otherwise); the slice-13 cross-language test closes that loop.
- Python 3.14 is the assumed target; `struct.Struct` has been stable since Python 3, so there is no version risk.

---

## ADR-014: Analytics operate on an external journal format; Java-side journaling is opt-in

**Status:** Accepted — slice 8

**Context.** Slice 3 built a memory-mapped journal (`Journal` in `engine/`) with a well-defined binary format. Slice 8 adds Python analytics whose natural input is that same journal. The shortest path to "we can replay a day of flow and compute VPIN over it" would be to wire `Journal` into `ExchangeServer` unconditionally so every run produces a journal file.

Two problems with that.

1. Journaling every record synchronously in the hot path adds `CRC32.update` per record and a memcpy into the mapped region. The benchmarks in §3 of `PERFORMANCE.md` do not currently include journaling overhead; if we enable it by default, the documented numbers become either incorrect or require a second column. That is a larger change than the slice needs.
2. The most immediate portfolio artefact (the simulator's PnL + latency chart) does not actually need a journal at all — the simulator has the send/receive timestamps in hand.

**Decision.** Slice 8 ships a journal *reader* (`journal_reader.py`) that parses Java-format journals, plus a *writer* usable by tests and the simulator. The `ExchangeServer` itself is not modified — journaling remains available as a class that a future CLI flag or slice can enable. The analytics CLI (`nx-analytics`) computes its artefacts from the simulator's in-memory run.

Three downstream consequences:

- **The VPIN test uses synthesised tape**, not a live-engine journal. That is the right level for a unit test anyway — a deterministic known-answer case rather than a live-data regression.
- **When ExchangeServer opt-in journaling lands** (expected alongside slice 13's ops polish), the existing `journal_reader` is already its intended consumer; no Python-side changes are needed.
- **Simulator latency histograms** come from the simulator's own timestamps, not from a journal. The `latency_analyzer` module accepts any `(send_ts, recv_ts)` stream, so the shape of the histogram is independent of the timestamp source.

**Consequences.** The shortest-path integration is deferred; in exchange, slice 7's benchmark numbers remain valid as documented, and slice 8 stays self-contained and testable without a live engine.


---

## ADR-015: WebSocket bridge batches per-client on a 16 ms window, drops oldest on backpressure

**Status:** Accepted — slice 9

**Context.** The feed arrives as UDP multicast at whatever rate the engine publishes (tens of thousands of updates/second under load). The browser cannot usefully repaint faster than its refresh rate, and even that is wasted work if most updates hit the same price level. A naïve pipeline — forward every datagram to every connected WebSocket as its own frame — would saturate both the browser event loop and the socket buffer under load. Two independent questions had to be answered:

1. **Where does coalescing happen, and over what window?**
2. **What does the bridge do when a specific client cannot keep up?**

**Decisions.**

1. **Per-client, per-window coalescing at ≈ 60 Hz (16 ms).** Each connected session owns its own `Batcher`. The UDP reader fans out every decoded message into every client's batcher; a per-client flush task wakes every 16 ms, snapshots the window, and enqueues one JSON frame to that client's outbox. Book updates are keyed by `(side, price)` — the latest update wins. Trades and exec reports are never coalesced (each is a distinct event). A snapshot supersedes pending book updates in the same window, because the snapshot is authoritative state and any deltas before it are stale. 16 ms is chosen to align roughly with `requestAnimationFrame` on a 60 Hz display; the dashboard (slice 10) applies messages on rAF so anything smaller is discarded anyway.

2. **Bounded outbox per client, drop-oldest on overflow, surface a `degraded` flag.** Each session has an `asyncio.Queue(maxsize=64)`. When full, the flush task drops the oldest frame, enqueues the latest, and sets a `degraded` bit that is attached to the next successful send. This gives the UI a chance to warn the user ("your connection is lagging") rather than silently drifting from true state. The alternative — letting the queue grow unboundedly, or waiting for `put()` — would couple one slow client to the speed of the rest. A disconnected, wedged TCP socket could then stall the flush loop of every other client sharing the UDP reader.

**Consequences.**

- The browser sees at most one frame per 16 ms per client, regardless of upstream rate. Under steady state that frame is small (one entry per modified level, trades appended, etc.).
- A client with a slow network loses older book updates, not the newest state. For an order-book UI, this is the right trade: the old mid was already overwritten. We do **not** drop trades or exec reports inside the batcher; they are list-appended and flushed in order.
- The per-client batcher copies the coalesced state on flush, so even if the WS writer is slow, the UDP reader continues to fold updates into the still-owned window. Memory is bounded by (number of live price levels × number of clients), which is small.
- **macOS loopback multicast requires naming the interface explicitly.** `IP_ADD_MEMBERSHIP` with `INADDR_ANY` works on Linux but silently drops loopback traffic on darwin. The bridge exposes `--multicast-interface` so local development can pass `127.0.0.1`; Linux containers leave it unset. This is an environmental footgun rather than a design choice, but worth writing down so the next person asking "why does my local feed go dark on mac" gets the answer in one grep.


---

## ADR-016: Dashboard dispatches WebSocket messages inside a single `requestAnimationFrame`, not on arrival

**Status:** Accepted — slice 10

**Context.** The bridge already coalesces the feed into ≈ 60 Hz batches (ADR-015), but each batch can still contain hundreds of book updates at peak rates. Two naïve consumer patterns both break under load:

1. **Dispatch inside `onmessage`.** React reconciles on every dispatch; at 10_000 msg/s the main thread does nothing but reducer work and diffing. The page drops to single-digit fps.
2. **Buffer in component state.** `setState` inside `onmessage` has the same problem, plus it forces intermediate renders nobody sees.

**Decision.** `useWebSocket` writes incoming frames into a ref-held inbox array and schedules a single `requestAnimationFrame` per mount. The rAF callback drains the inbox in insertion order and calls `onMessage` once per frame with the accumulated work. Handlers themselves are stored in refs so the hook never reconnects when parent state changes.

**Why this specifically.**

- One drain per frame aligns React's render cycle with the display's refresh. Anything finer is discarded by the browser anyway.
- A ref-held inbox avoids React state entirely for the arrival path — no re-render is triggered until the drain decides to dispatch.
- A ref for `onMessage` means the caller can close over reducer state (which changes every batch) without cycling the socket. Reconnect only happens when the URL changes.

**Consequences.**

- Tests mock `requestAnimationFrame` deterministically: enqueue messages via the WS mock, advance the rAF queue manually, assert dispatches. See `useWebSocket.test.ts`.
- Under StrictMode (dev-only) the effect runs twice. The cleanup closes the first socket and cancels its rAF loop before the second connects — no duplicate dispatches leak.
- A disconnect triggers a 1 s fixed-delay reconnect. Exponential backoff is overkill here (the bridge is on localhost in dev, same datacenter in prod); a constant retry keeps the "OFFLINE → LIVE" banner reactive.
- Book-update state uses `Record<number, number>` rather than `Map`. React's referential comparison trips over `Map` mutations; a freshly-constructed record object each reducer step gives memoised selectors clean inputs. The allocation is cheap relative to the render cost a bad equality check would cause.
