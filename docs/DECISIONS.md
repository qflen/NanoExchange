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
