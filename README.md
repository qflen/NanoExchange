# NanoExchange

A zero-allocation matching engine and full-stack exchange simulator, from
byte-level wire protocols to a 60 fps React dashboard.

![Java 21](https://img.shields.io/badge/Java%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Python](https://img.shields.io/badge/Python-3776AB?style=for-the-badge&logo=python&logoColor=white)
![React 18](https://img.shields.io/badge/React%2018-20232A?style=for-the-badge&logo=react&logoColor=61DAFB)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white)
![Tailwind](https://img.shields.io/badge/Tailwind-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white)
![UDP Multicast](https://img.shields.io/badge/UDP%20Multicast-0052CC?style=for-the-badge&logo=wireshark&logoColor=white)
![JMH](https://img.shields.io/badge/JMH-FF6B35?style=for-the-badge&logo=openjdk&logoColor=white)

![Dashboard demo](docs/screenshots/nanoexchange_demo.gif)

Live dashboard under the random-order simulator: free-floating Order Book, OHLC Price chart
with 3s/10s/1m/3m timeframes, Order Entry, Depth heatmap, Trade tape, Metrics, and Latency
monitor. Three themes in the top-right toggle: dark, light, and a
colorblind-safe palette (blue/orange in place of green/red). Deep dives in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) (processes, threads, memory model),
[`docs/PROTOCOL.md`](docs/PROTOCOL.md) (byte-level wire formats),
[`docs/PERFORMANCE.md`](docs/PERFORMANCE.md) (benchmark methodology and results), and
[`docs/DECISIONS.md`](docs/DECISIONS.md) (20 ADRs).

---

## What it is

A self-contained, CLOB-style matching engine plus the production infrastructure around it:
binary TCP order gateway, UDP multicast market-data feed with snapshot + incremental recovery,
deterministic memory-mapped journal, Python client library, WebSocket bridge, React dashboard,
and JMH benchmarks that quantify every layer. The hot path is allocation-free after warmup
across all order types (LIMIT, MARKET, IOC, FOK, ICEBERG), verified by JMH `-prof gc`; the
dashboard holds 60 fps under 10 k market-data msg/s because every WebSocket message is queued
and drained inside a single `requestAnimationFrame` per tick.

## Architecture

![Architecture](docs/screenshots/architecture.svg)

Three processes. Two wire protocols (binary TCP for order entry, UDP multicast for market
data). One JSON envelope for the browser. Source for the diagram above:
[`docs/architecture.d2`](docs/architecture.d2). The component-level diagrams live in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md); the byte-level layouts live in
[`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Measured performance

Numbers are per-component, not end-to-end. The 10 k figure is the dashboard/bridge
ceiling; the engine is three orders of magnitude higher.

| Component                              | Metric                      | Result                |
|----------------------------------------|-----------------------------|-----------------------|
| `MatchingEngine.process` resting limit | throughput                  | **33.9 M ops/s**      |
|                                        | latency / op                | 29.5 ns               |
|                                        | allocation                  | 0 B/op after warmup   |
| `MatchingEngine.process` 5-level sweep | throughput                  | 6.5 M ops/s           |
|                                        | latency / op                | 155 ns                |
| `RingBuffer` SPSC hand-off             | throughput                  | 58 M ops/s            |
|                                        | vs `ArrayBlockingQueue`     | ~1.3× faster, ~4× less variance |
| `WireCodec` NEW_ORDER encode           | throughput                  | 28.6 M ops/s (35 ns)  |
| `WireCodec` NEW_ORDER decode           | throughput                  | 27.9 M ops/s          |
| Dashboard under 10 k msg/s load        | frame rate                  | **60.0 fps sustained**|
|                                        | p99 frame time              | 17.8 ms               |
|                                        | longest task                | 42 ms                 |

Apple M5 · JDK 21.0.10 · macOS 26.1 · JMH 1.37 default config. Methodology, flamegraph
pointers, and interpretation notes in [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md).

## What makes this stand out

- **Zero-allocation hot path, end-to-end.** Pooled orders, pooled execution reports, a
  length-prefix codec that writes into a pre-sized `ByteBuffer`, and a `LongHashMap` keyed by
  primitive `long` so order-ID lookups never box. JMH `-prof gc` is the contract, not an
  afterthought.
- **Deterministic replay.** Every input event and every emitted report is journaled to a
  memory-mapped file framed with CRC32. Replaying the file into a fresh engine reproduces
  the output stream byte-for-byte, which is how the restart test proves the engine is
  deterministic ([ADR-008](docs/DECISIONS.md#adr-008-memory-mapped-append-only-journal)).
- **Real wire protocols, documented to the byte.** Little-endian, length-prefix-framed,
  CRC-checked binary TCP for order entry. UDP multicast with monotonic sequence numbers and
  snapshot + incremental recovery for market data. Both specified in
  [`docs/PROTOCOL.md`](docs/PROTOCOL.md) with hex examples, not English.
- **Frame-accurate dashboard instrumentation.** Incoming WebSocket messages are not
  dispatched to React on arrival; they are queued and drained inside a single
  `requestAnimationFrame` per tick
  ([ADR-016](docs/DECISIONS.md#adr-016-dashboard-dispatches-websocket-messages-inside-a-single-requestanimationframe-not-on-arrival)).
  Frame metrics use `useSyncExternalStore` so the LatencyMonitor re-renders at 1 Hz while
  the rest of the UI re-renders at 60 Hz
  ([ADR-018](docs/DECISIONS.md#adr-018-frame-rate-instrumentation-via-usesyncexternalstore-virtualisation-by-level-count)).
- **Analytics worth running.** A Python analytics package computes VPIN (Easley / López de
  Prado / O'Hara, 2012) off the journal, renders a latency histogram and depth heatmap, and
  includes a market-making simulator that drives the live engine via the TCP gateway. See
  `make analytics`.

## Quick start

Prerequisites: Python ≥ 3.11 and Node ≥ 20. JDK 21 is fetched automatically by the
Gradle wrapper via the foojay resolver.

```bash
git clone https://github.com/qflen/NanoExchange.git && cd NanoExchange
./run.sh
```

First run auto-bootstraps the Python venv and dashboard npm packages, builds the
engine, then starts all three processes. Open http://localhost:5173. Ctrl-C tears
everything down. `./run.sh --help` explains each piece.

To run the full test suite across Java, Python, and the dashboard:

```bash
./gradlew check
.venv/bin/pytest client/tests bridge/tests analytics/tests
npm --prefix dashboard test -- --run
```

## Tradeoffs & future work

- **The price-level container is still a sorted array.** ADR-005 pins this as a deliberate
  tradeoff for shallow books; the JMH numbers agreed when I measured it. The first time I
  profile a thousand-level book under realistic cancel churn I expect a B-tree-of-arrays to
  beat it, and the replay machinery makes the swap safe. It is in the backlog, not shipped.
- **MPSC ring buffer.** The current SPSC hand-off is fine for one gateway thread, but the
  moment a second matching engine (different instrument) appears, the gateway wants to fan
  out. MPSC with a claim strategy is half a day of work; it is in the backlog because this
  build did not need it.
- **Cross-language protocol test.** Python's `struct` layouts and Java's `ByteBuffer` calls
  agree today because I wrote them both and PROTOCOL.md is the source of truth. A
  byte-for-byte round-trip test that generates frames from both stacks would catch silent
  drift. In the backlog.
- **Playwright E2E.** Vitest covers the components; a Playwright run that submits an order
  and asserts the exec-report lands in the Open Orders table would be the last mile. Out of
  scope here.

## License

MIT
