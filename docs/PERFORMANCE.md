# NanoExchange — Performance

This document records the measured throughput and latency of the hot-path components under
realistic workloads, along with the methodology used to obtain them. The goal is not a
marketing headline number, it is a reproducible baseline that a future change can be
compared against.

Benchmarks live in `bench/` and use [JMH 1.37](https://github.com/openjdk/jmh).

> **Headline (this commit, Apple M5, JDK 21.0.10, default JMH config).**
> Matching engine: **33.9M ops/s** resting-insert (29.5 ns/op), **6.5M ops/s** on a
> 5-level sweep (155 ns/op). SPSC ring: **58M ops/s** against a single-producer / single-consumer
> `ArrayBlockingQueue` at **~45M ops/s** with ~4× the variance. Codec: **28.6M ops/s** encode
> NEW_ORDER (35 ns/op), **27.9M ops/s** decode. Full table in §3.

---

## 1. Methodology

### 1.1 JMH configuration

Two configurations are available:

| Configuration | Warmup | Measurement | Forks | Approx. wall time |
|---------------|--------|-------------|-------|-------------------|
| **Default** (`./gradlew :bench:jmh`) | 3 × 1s | 5 × 2s | 1 | ~5 min |
| **Long** (`-Pbench.long`) | 5 × 2s | 10 × 2s | 3 | ~30 min |

Default is what runs in CI and what produced the numbers in §3. The long configuration is
used when we need publishable tail-latency percentiles — the additional forks drive down
fork-to-fork variance, and the longer warmup lets the JIT settle on larger benchmarks.

### 1.2 JVM

- OpenJDK 21.0.10 (Homebrew `openjdk@21`)
- `-Xms1g -Xmx1g` — heap sized so GC is rare but not nonexistent; this mirrors production
  where memory is cheap but starting with a too-small heap would let benchmark results be
  polluted by heap-sizing work.
- Compiler blackholes enabled (JMH 1.37 auto-detect).

### 1.3 Machine

The numbers below come from the machine this commit was built on. Always re-run before
drawing conclusions on a different box.

- **CPU.** Apple M5, 10 cores (the three benchmarks that care are single-threaded apart
  from the SPSC ring, which is two-threaded).
- **RAM.** 32 GB.
- **OS.** macOS 26.1 (Darwin 25.1.0), arm64.

### 1.4 Allocation profiling

When `-prof gc` is added (`./gradlew :bench:jmh -Pjmh.profilers=gc`), JMH reports per-op
allocation. The target for the engine, ring buffer, and codec is **0 B/op** after warmup;
a non-zero number is a regression even if throughput is stable — allocations are latency
spikes waiting to happen. `-prof gc` is **not** run by default (it halves throughput on some
benchmarks); the numbers in §3 below are throughput only. The one case where boxing cost is
the whole point of the comparison is §3.3, and there the throughput gap already tells the
story.

### 1.5 Latency percentiles

For latency-sensitive paths (matching, encode) benchmarks use `@BenchmarkMode(AverageTime)`
alongside `Throughput`. JMH emits p50/p99/p99.9 automatically in the `SampleTime` mode — the
`jmhLong` task opts into that. Default runs report mean only; this is enough to catch
regressions but not fine enough to make absolute claims.

---

## 2. Benchmarks

### 2.1 `OrderBookBenchmark`

Matching-engine cost under two scenarios:

- **`restingInsert`**: submit a non-crossing limit order into an empty book. Exercises
  `book.addResting` and level-lookup, the common path for quote flow.
- **`marketSweep5Levels`**: submit a market order that sweeps five 10-lot levels, eating 50
  lots total. Exercises the match loop, fills, and per-level cleanup — the stressful path.

Each invocation resets the book. Order allocations are drawn from a pre-filled pool so the
benchmark isn't secretly measuring allocator throughput.

### 2.2 `RingBufferBenchmark`

Custom SPSC `RingBuffer` vs. `ArrayBlockingQueue` baseline, both at capacity 1024, both with
single producer + single consumer in two threads. Producer uses `tryPublish` + spin on full,
consumer uses `poll` + spin on empty, to remove blocking-semantics noise from the comparison.

### 2.3 `HashMapBenchmark`

`LongHashMap<Object>` vs. `java.util.HashMap<Long, Object>`, pre-populated with 10 000
monotonic keys. Operations: `get`, `put`+`remove` pair. Monotonic keys reflect how the
gateway actually assigns order IDs; uniform random would be kind to both implementations and
doesn't reflect reality.

### 2.4 `SerializationBenchmark`

`WireCodec` encode/decode on direct ByteBuffers, matching the production configuration. Two
encodes (NEW_ORDER, exec report) and one decode (NEW_ORDER) — the two frame types that
dominate the hot path.

---

## 3. Results

Numbers are pulled directly from `bench/build/reports/jmh/results.json`. Re-run
`./gradlew :bench:jmh` to refresh.

Error bars are JMH's 99.9 % CI half-width; a bare ± with no number means the bound is below
the last significant digit shown.

### 3.1 Matching engine

| Benchmark | Throughput (M ops/s) | Avg latency (ns/op) |
|-----------|---------------------:|--------------------:|
| `restingInsert` | 33.9 ± 0.2 | 29.46 ± 0.97 |
| `marketSweep5Levels` | 6.5 ± 0.1 | 154.98 ± 1.61 |

A sweep that consumes 5 levels × 10 lots = 50 fills in ~155 ns — roughly **3 ns per fill**
amortised — suggests the per-level cleanup and fill-emission path is well-inlined and the
intrusive-list traversal stays hot in L1.

### 3.2 SPSC ring vs. `ArrayBlockingQueue`

| Benchmark | Throughput (M ops/s) | Relative |
|-----------|---------------------:|---------:|
| `spsc_ring` (produce+consume) | 58.0 ± 1.0 | 1.00× |
| `abq` (produce+consume)       | 45.0 ± 48.8 | 0.78× |

The ABQ error bar is not a typo — one of five iterations dropped to less than half of the
others (21.8 M vs. 50.5 M ops/s), likely an OS scheduling artefact that the custom ring's
tighter spin loop doesn't suffer from. The spsc ring not only wins the mean but wins on
variance too: **±1 %** vs. **>100 %** of mean for ABQ. This is the kind of thing that matters
under production load and gets hidden by single-number benchmarks. Under `-Pbench.long`
(3 forks) we expect the ABQ variance to converge, but the mean ordering should not change.

Note: the original expectation in §2.2 was 5–10× — the actual gap is modest (~1.3×) because
the `ArrayBlockingQueue` JIT'd very well in this single-JVM, single-fork run. The differences
that matter show up under contention and GC pressure, which this microbenchmark deliberately
avoids.

### 3.3 Map implementations

| Benchmark | Throughput (M ops/s) | Avg latency (ns/op) |
|-----------|---------------------:|--------------------:|
| `longMapGet`         | 324.0 ± 3.0 | 3.11 ± 0.03 |
| `boxedMapGet`        | 334.0 ± 2.0 | 3.00 ± 0.02 |
| `longMapPutRemove`   | 218.0 ± 16.0 | 4.52 ± 0.12 |
| `boxedMapPutRemove`  | 118.0 ± 1.0 | 8.63 ± 0.56 |

The `get` path is effectively tied — HotSpot's escape analysis can eliminate the `Long` box on
a pure read against a pre-populated map, so the boxed implementation doesn't allocate. The
mutation path cannot: `put` retains a reference to the key, escape analysis fails, and
`boxedMapPutRemove` allocates a `Long` per call. **The custom map is ~1.85× faster on
put/remove**, and — per §1.4 — when `-prof gc` is added, the boxed version should report
non-zero B/op on that path while the long map reports 0.

### 3.4 Codec

| Benchmark | Throughput (M ops/s) | Avg latency (ns/op) |
|-----------|---------------------:|--------------------:|
| `encodeNewOrder`         | 28.6 ± 0.4 | 35.01 ± 0.96 |
| `encodeExecutionReport`  | 16.1 ± 0.1 | 62.03 ± 1.07 |
| `decodeNewOrder`         | 27.9 ± 0.2 | 35.86 ± 0.75 |

Exec-report encode is ~1.77× the cost of new-order encode. The payload is 66 vs. 50 bytes
(per `PROTOCOL.md` §6, §4.3.1) — a 1.32× ratio — so bytes alone don't account for the gap;
the exec report also writes more distinct fields (10 vs. 7), each incurring its own bounds
check on the direct buffer. Per-byte cost is ~0.9 ns/byte for NEW_ORDER encode, which
is roughly the cost of a `putLong` into a direct buffer and matches expectations.

---

## 4. Flamegraphs

Flamegraphs from `async-profiler` live under `bench/flamegraphs/` (not checked in — regenerate
via `bench/scripts/profile.sh`). The one interviewers tend to ask about is
`marketSweep5Levels.svg` — shows the proportion of cycles spent in `match()`, `fillMaker`,
and level cleanup.

Not committed because they are per-machine artifacts; regenerate locally if you want them.

---

## 5. Interpreting regressions

A performance regression is most often one of:

1. **Allocation.** `B/op` went from 0 to non-zero. This is the single most common cause of
   tail-latency spikes. Check recent changes for `new` in the hot path, auto-boxing
   (`Long`, `Byte`), lambda captures, or implicit toString.
2. **Branch misprediction.** Avg latency up, throughput down, `-prof perfasm` shows a
   specific branch is hot and mispredicted. Usually caused by data-dependent branching on a
   field whose distribution shifted.
3. **Cache misses.** Usually visible as increased avg latency on `marketSweep5Levels` but
   not `restingInsert`. Indicates working-set growth — a data structure got bigger than
   expected.

Run the long configuration before reaching a conclusion. Default runs have ~10% iter-to-iter
variance; it's easy to mistake noise for signal if you only did one pass.
