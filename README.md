# NanoExchange

A zero-allocation matching engine in Java, a UDP multicast market-data feed, a Python client
and WebSocket bridge, and a React dashboard that renders a live order book at 60 fps under
10 k msg/s.

![Dashboard screenshot](docs/screenshots/dashboard.png)

Live dashboard under the random-order simulator: free-floating Order Book, OHLC Price chart
with 3s/10s/1m/3m timeframes, Order Entry, Depth heatmap, Trade tape, Metrics, and Latency
monitor. Architectural depth dive in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), wire
formats in [`docs/PROTOCOL.md`](docs/PROTOCOL.md), benchmark numbers in
[`docs/PERFORMANCE.md`](docs/PERFORMANCE.md), and every non-obvious call in
[`docs/DECISIONS.md`](docs/DECISIONS.md).

---

## What it is

NanoExchange is a self-contained, CLOB-style matching engine with the full infrastructure
that would surround one in production: a binary TCP order gateway, a UDP multicast market-data
feed with snapshot + incremental recovery, a deterministic memory-mapped journal, a Python
client library, a WebSocket bridge, a real-time React UI, and JMH benchmarks that quantify
every layer.

It is explicitly **not** a toy. The matching engine holds zero allocations on its hot path
after warmup, across all supported order types (LIMIT, MARKET, IOC, FOK, ICEBERG), verified
with JMH `-prof gc`. The ring buffer outperforms `ArrayBlockingQueue` by ~1.3×. The dashboard
stays at 60 fps while processing 10 k market-data messages per second because every incoming
WebSocket message is drained inside a single `requestAnimationFrame`, not on arrival.

## Architecture

```mermaid
flowchart LR
    subgraph engine[Java engine JVM]
        GW[Gateway thread<br/>NIO Selector]
        RB1[(inbound ring)]
        ENG[Engine thread<br/>MatchingEngine]
        RB2[(outbound ring)]
        MD[Multicast publisher]
        JRN[(memory-mapped<br/>journal)]
        GW -->|decode TCP frames| RB1 --> ENG --> RB2 -->|encode| GW
        ENG --> MD
        ENG --> JRN
    end

    CLIENT[Python client<br/>nanoexchange_client]
    BR[Python WebSocket bridge<br/>nanoexchange_bridge]
    DASH[React dashboard<br/>dashboard/]
    ANL[Python analytics<br/>VPIN, simulator, load test]

    GW <-->|binary TCP :9000| CLIENT
    MD -->|UDP multicast 239.200.6.42| CLIENT
    GW <-->|binary TCP :9000| BR
    MD -->|UDP multicast 239.200.6.42| BR
    BR <-->|JSON WebSocket :8765| DASH
    JRN -.->|offline replay| ANL
```

Three processes. Two wire protocols (binary TCP for order entry, UDP multicast for market
data). One JSON envelope for the browser. The component-level diagrams live in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md); the byte-level layouts live in
[`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Quick start

Prerequisites: JDK 21, Python ≥ 3.11, Node ≥ 20.

```bash
# 1. Build the Java side. The wrapper auto-downloads JDK 21 on first run
#    (via the foojay resolver in settings.gradle.kts) if it isn't installed.
./gradlew build

# 2. Create a Python venv and install the three sibling packages in editable mode.
python3 -m venv .venv
.venv/bin/pip install -e "client[dev]" -e "bridge[dev]" -e "analytics[dev]"

# 3. Install the dashboard's npm deps and run the full test suite.
make dashboard-install
make test

# 4. One command brings up the engine, bridge, and dashboard together.
./run.sh
#    Open http://localhost:5173. Ctrl-C tears all three processes down.
#    See ./run.sh --help for what each piece does.
```

`docker compose up` is the alternative for CI-style reproducibility — see the
[Docker notes](#docker-notes) for multicast caveats.

## Measured performance

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

## Layout

```
engine/                 Java — pooled order book, matching engine, ring buffer, journal
network/                Java — TCP order gateway + UDP multicast publisher + binary codecs
bench/                  Java — JMH benchmarks (headline numbers live here)
client/                 Python — TCP order client + UDP feed handler + book builder
analytics/              Python — VPIN, latency histograms, market-maker simulator
bridge/                 Python — UDP/TCP ↔ WebSocket with per-client rAF-window batching
dashboard/              React 18 + TypeScript + Tailwind — the live UI
docs/
├── ARCHITECTURE.md     process + thread + memory model
├── BUILD_PLAN.md       execution plan and order-of-construction
├── DECISIONS.md        20 ADRs — every non-obvious call made in this repo
├── PERFORMANCE.md      benchmark methodology, results, flamegraph pointers
├── PROTOCOL.md         wire + journal + feed formats, byte-level
└── screenshots/
```

## Docker notes

`docker compose up` builds and starts three containers: `engine`, `bridge`, and `dashboard`
(nginx-served static build). The engine and bridge run with `network_mode: host` on Linux —
UDP multicast inside a user-defined bridge network is flaky and the loopback-interface dance
that macOS needs does not translate cleanly to Docker. On macOS, Docker Desktop's VM does
not forward multicast at all; run the engine and bridge natively (or in a Linux VM) and keep
`docker compose` for CI-style reproducibility.

## CI

GitHub Actions runs on every push and PR:

- `java`: `./gradlew check` with a cached `~/.gradle`
- `python`: `pytest` across `client/`, `bridge/`, and `analytics/` with a cached `pip` dir
- `dashboard`: `vitest run` and `vite build` with a cached `node_modules`

Benchmarks (`./gradlew :bench:jmh`) are not run in CI — they are noisy on shared hardware
and the numbers in [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md) come from a controlled
machine. Nightly benchmark runs are in the backlog.

## Reflection — what I would do differently

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
