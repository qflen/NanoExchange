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
