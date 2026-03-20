# Architecture

This document is a companion to the overview diagram in the top-level `README.md`. The
README shows **processes and wire protocols**. This document goes inside each process: the
thread topology of the Java engine, the memory layout of the pooled data structures, the
end-to-end lifecycle of one order, and the React component hierarchy behind the dashboard.

Byte-level protocol tables are in [`PROTOCOL.md`](PROTOCOL.md); performance measurements are
in [`PERFORMANCE.md`](PERFORMANCE.md); the rationale for individual choices is in
[`DECISIONS.md`](DECISIONS.md). This document explains how the pieces fit together.

---

## 1. Process topology

Three long-running processes make up a running NanoExchange:

| Process     | Language | Purpose                                                   |
|-------------|----------|-----------------------------------------------------------|
| `engine`    | Java     | Matching + order gateway + multicast market-data feed     |
| `bridge`    | Python   | UDP multicast + TCP binary ↔ WebSocket JSON, per-client batching |
| `dashboard` | static   | React 18 SPA served over nginx in production              |

The Python client library (`client/`) is not a separate process; it is imported by the
bridge and by the analytics / simulator / load-test scripts. The analytics package
(`analytics/`) runs on demand against an offline journal file — it has no network presence.

---

## 2. Thread topology inside `engine`

The Java engine JVM runs on exactly **two business threads** plus JVM infrastructure:

```
+-----------------------------+            +-----------------------------+
|      Gateway thread         |            |       Engine thread         |
|   (NIO selector, TCP I/O)   |            |   (matching + feed publish) |
|                             |            |                             |
|  - accept TCP connections   |            |  - spin-read inboundRing    |
|  - read frames, decode      |   ring     |  - MatchingEngine.process   |
|  - push InboundEvent into   +---------->+|  - publish feed messages    |
|    inboundRing              |  SPSC      |  - push OutboundFrame into  |
|  - drain outboundRing,      |            |    outboundRing             |
|    write to client sockets  +<----------+|  - append to journal        |
|                             |   ring     |                             |
+-----------------------------+            +-----------------------------+
         ^                                              |
         | TCP (binary, length-prefix, CRC)             | UDP multicast (binary)
         |                                              v
   +-----+------+                                +------+---------+
   |  clients   |                                | subscribers    |
   +------------+                                +----------------+
```

Two rings, both SPSC (one producer, one consumer):

- **`inboundRing`** — Gateway → Engine. Elements are pooled `InboundEvent` structs
  (`ExchangeServer.InboundEvent`) carrying one of `EV_NEW_ORDER`, `EV_CANCEL`, or
  `EV_MODIFY` plus all fields decoded from the wire.
- **`outboundRing`** — Engine → Gateway. Elements are pooled `OutboundFrame` structs
  containing a pre-encoded `ByteBuffer` and the target `ClientSession`. The gateway thread
  writes each frame to the owning socket in its next selector iteration.

Neither ring is `java.util.concurrent`; both are
[`RingBuffer`](../engine/src/main/java/com/nanoexchange/engine/RingBuffer.java) with
cache-line-padded sequence cursors ([ADR-009](DECISIONS.md#adr-009-manual-cache-line-padding-on-ring-buffer-sequences)).
The producer CAS-claims a slot; the consumer spin-waits with `Thread.onSpinWait()`.

### Why two threads and not more?

- The gateway thread does **only** I/O and decoding. No matching work runs there, because
  selector threads that block in application code regress across all connections. See
  [ADR-011](DECISIONS.md#adr-011-nio-selector-on-a-dedicated-thread-zero-engine-work-on-the-network-thread).
- The engine thread does matching *and* multicast publishing. Multicast send is
  non-blocking (UDP socket, best-effort), so the engine does not stall on it. Keeping the
  feed publisher on the engine thread avoids a third ring and guarantees that every book
  update is published in the same order it was applied — the feed is the journal's twin.
- The engine thread also writes journal records. `MappedByteBuffer` writes are memory
  copies; they do not hit the disk unless we call `force()` ([ADR-008](DECISIONS.md#adr-008-memory-mapped-append-only-journal)).
  `force()` is called at batch boundaries and on graceful shutdown, never per record.

### Determinism contract

The engine thread must be deterministic so that journal replay reproduces the output stream
byte-for-byte. That means:

- No wall-clock calls in matching logic. `timestampNanos` comes off the wire from the
  client; the engine stamps its own events via an explicit counter that replay can reset.
- No `HashMap` iteration. The custom `LongHashMap` iterates in insertion order for our
  purposes, but the engine never iterates the map on the hot path anyway — it is a lookup
  structure.
- No thread-local randomness. Every random draw in the load test / simulator is seeded.

Stage 3's replay test is how this contract is enforced.

---

## 3. Memory layout — pooled objects on the hot path

Five pooled types live in the engine JVM. All are allocated once at startup and reused
forever; the hot path never calls `new`.

| Type                    | Pool size (default) | Cleared on release? | Notes                                |
|-------------------------|---------------------|---------------------|--------------------------------------|
| `Order`                 | 1 048 576           | yes (`reset()`)     | Intrusive `prev`/`next` for per-price-level FIFO |
| `ExecutionReport`       | 65 536              | yes                 | Returned to caller, consumed by outbound encoder |
| `PriceLevel`            | 8 192               | yes                 | Contains head/tail pointers for `Order` FIFO |
| `ExchangeServer.InboundEvent`  | 8 192        | yes                 | One ring-buffer slot worth of decoded input |
| `ExchangeServer.OutboundFrame` | 8 192        | yes (buffer cleared)| Pre-encoded ByteBuffer + target session |

All pools use the same [`ObjectPool`](../engine/src/main/java/com/nanoexchange/engine/ObjectPool.java):
a Vyukov-style MPMC ring buffer with `VarHandle` CAS on `long` sequence counters
([ADR-003](DECISIONS.md#adr-003-object-pooling-via-vyukov-mpmc-ring-buffer)).
`acquire()` returns `null` on exhaustion rather than throwing, so the hot path pays one
branch, not an exception.

### Cache-line layout of `Order`

Fields are ordered to keep matching-relevant hot data in the first cache line:

```
 long orderId          // 8
 long clientId         // 16
 long price            // 24   fixed-point × 10⁸
 long quantity         // 32
 long displaySize      // 40   iceberg tip; 0 for non-iceberg
 long timestampNanos   // 48
 byte side             // 49
 byte orderType        // 50
 byte status           // 51
 // 5 bytes padding    // 56
 Order prev            // 64   second cache line
 Order next            // 72
```

The `prev`/`next` pointers are intrusive: they live inside `Order` itself, not in a
separate linked-list-node object, which is what lets the per-price-level FIFO run allocation-free.

### Journal format

Each event and each emitted execution report appends one record:

```
8 bytes sequence | 4 bytes length (N) | N bytes payload | 4 bytes CRC32
```

The payload is opaque to the journal; stage 4 defines how order / cancel / modify / report
messages serialize into it. See [`PROTOCOL.md §1`](PROTOCOL.md#1-journal-record-format-stage-3)
for the full byte-level layout including end-of-log detection and torn-write recovery.

---

## 4. Order lifecycle — end to end

Following a single LIMIT BUY order from the browser through the system and back:

```
 t=0    Browser                 OrderEntry.tsx submit → send() over WebSocket
        JSON:  {"type":"new_order", "client_order_id":...,
                "side":"BUY", "order_type":"LIMIT",
                "price": 100.25, "quantity": 10, ...}

 t≈1ms  Bridge (Python)         ws_bridge.py receives JSON on the session socket
        → translator turns it into a binary NEW_ORDER frame
        → OrderClient (one TCP connection per browser session) sends it

 t≈1ms  Engine gateway thread   NIO selector read, WireCodec decodes the frame
        → InboundEvent pooled acquire, fields populated
        → inboundRing.claim().write(event).commit()

 t≈2µs  Engine thread           inboundRing.pollNext()
        → MatchingEngine.process(event)
          • Order acquired from pool, fields set
          • OrderBook.addLimit(order):
              - look up PriceLevel for price (LongHashMap, no box)
              - if absent, acquire PriceLevel, splice into sorted array
              - append Order to level's tail (intrusive linked list)
              - update BBO cached pointers if this price is the new best
          • ExecutionReport acquired: ACK with orderId, clientId, remaining=10
        → publishFeedUpdate(level): build BOOK_UPDATE, send on multicast
          with feedSequence++ on the engine thread's publisher
        → journal.append(seq, payload) for both the NEW_ORDER and the ACK
        → outboundRing.claim().writeReport(ack).commit()

 t≈3µs  Gateway thread          drains outboundRing
        → encodes ACK with WireCodec into the session's write buffer
        → selector next tick: writes buffer to client socket

 t≈1ms  Bridge                  OrderClient.reports() yields the decoded ACK
        → translator → batcher.add(exec_report_json)
        → batcher's 16 ms window ends: flushes {"type":"batch", ...} to WS

 t≈1ms  Browser                 WebSocket onmessage → inbox.push(msg)
                                (does NOT dispatch yet — next rAF will)
        next rAF tick: drain inbox → dispatch('batch') → exchangeReducer
        → ExchangeContext value updates once per frame, not once per message
        → OrderEntry's Open Orders table re-renders with the new order

 t≈8ms  Other browsers          independent WebSocket sessions receive the
                                same BOOK_UPDATE via the multicast path
                                (bridge forwards multicast → WS unrelated
                                 to who submitted the order)
```

The total path from browser to ack is ≈ 3 ms on localhost, dominated by WebSocket send/recv
and the 16 ms rAF batching window (on average, half the window is waiting time).

### Cancel path

Shorter: client → CANCEL frame → gateway decodes → inboundRing → engine →
`OrderBook.cancel(orderId)` which unsplices the order from its price-level list,
releases the order back to the pool, and emits a CANCELED report. Market-data side: if
the price level went to zero, publish a BOOK_UPDATE with aggregateQty=0 (signal to clear
the level on consumers).

### Self-trade prevention

Before matching, the engine checks `incoming.clientId == maker.clientId`; if equal, the
incoming order is rejected ([ADR-006](DECISIONS.md#adr-006-self-trade-prevention-policy-reject-the-incoming-order)).
No match, no fill, one REJECTED report, no partial exposure of a self-cross on the feed.

---

## 5. Market-data path

The engine thread owns a single `MarketDataPublisher` that writes to one UDP socket bound
to a configured multicast group ([PROTOCOL.md §3](PROTOCOL.md#3-market-data-feed-stage-5)).
Sequence numbers are monotonic **across all message types** — a consumer detects a gap
purely by seeing a jump in feed sequence, no per-type bookkeeping.

### Snapshot cadence

Every one second (configurable), the engine publishes a `SNAPSHOT` composed of one or more
chunked datagrams. The snapshot's `startingSequence` is the feed sequence of the last
incremental included in it; consumers that joined mid-stream or detected a gap wait for the
next snapshot, apply it, discard any buffered incrementals with `seq ≤ startingSequence`,
and resume from there. The `BookSnapshotBuilder` lives in `network/feed/`.

### Consumer reconstruction

The Python client and the bridge both use the same `book_builder.BookBuilder` pattern: keep
a `{side: {price: aggregateQty}}` map; apply each `BOOK_UPDATE` (delete on qty=0); replace
wholesale on each completed snapshot.

---

## 6. Bridge architecture

The bridge is one asyncio process that runs three concurrent loops per browser session plus
a single upstream multicast reader shared across all sessions:

```
                upstream multicast (one reader)
                        |
                        v
           +------------+-----------+
           |  FeedHandler (asyncio) |
           +------------+-----------+
                        |
                        v    fanned out to every connected session
   +--------+-----------+-----------+--------+
   |        |           |           |        |
   v        v           v           v        v
  S1       S2          S3          S4       Sn       (one ClientSession per browser tab)
   |        |                                 |
   |  Batcher (16 ms window, per-client)      |
   |                                          |
   v                                          v
  WebSocket out                       WebSocket out

  Meanwhile, for each session that has order entry enabled:
  session -> OrderClient -> TCP to engine gateway
  OrderClient.reports() -> translator -> batcher.add() (same batch as market data)
```

Key shapes:

- **One upstream, many downstream.** The multicast reader is shared. Per-session queues
  are bounded; slow clients get drop-oldest with a `degraded` flag, not backpressure on the
  producer ([ADR-015](DECISIONS.md#adr-015-websocket-bridge-batches-per-client-on-a-16-ms-window-drops-oldest-on-backpressure)).
- **Per-session order client.** Each browser tab gets its own TCP connection to the
  gateway ([ADR-017](DECISIONS.md#adr-017-bridge-opens-one-tcp-orderclient-per-browser-session-exec-reports-fold-into-the-same-batch-stream)).
  That is isolation, not multiplexing: a tab's ACKs and fills are routed only to that tab,
  while the market-data feed is shared.
- **One batch stream per session.** Exec reports and book updates land in the same
  16 ms batcher, flushed in one JSON `batch` frame. The dashboard's reducer applies a batch
  in one call, which is one re-render per frame regardless of update volume.

---

## 7. Dashboard architecture

### Data flow

```
 useWebSocket ──── rAF drain ────► exchangeReducer ───► ExchangeContext
       ^                                                       |
       | send(order)                                           | state
       |                                                       v
 OrderEntry ◄── useOrderSubmit ────── components read via useContext
                                      (OrderBookLadder, DepthChart,
                                       TradeTape, PriceChart, ...)
```

- **`useWebSocket`** holds the socket in a ref and pushes every `onmessage` payload into a
  ref-held inbox array. A single `requestAnimationFrame` loop drains the inbox via one
  `dispatch(...)` call per frame. React does not see messages; React sees frames.
- **`exchangeReducer`** is a plain `useReducer`. State shape: `{ bids, asks, trades,
  executions, myOrders, connected, degraded, seq }`. Applying a `batch` action folds every
  contained book update, trade, and exec into state in one pass.
- **`ExchangeContext`** wraps reducer state and the `send` function in a single provider.
  `send` goes straight to the socket ref — no React state for outgoing messages.

### Component tree

```
<App>
  <ExchangeProvider>                   (wraps useWebSocket + useReducer)
    <Header>
      <ConnectionStatus/>              (LIVE / DEGRADED / OFFLINE)
    </Header>
    <Grid>                             (12 cols × 6 rows)
      <OrderBookLadder/>               (ROWS_PER_SIDE = 12; flash on qty change)
        OR <VirtualizedLadder/>        (auto-swapped when bid+ask levels > 100)
      <DepthChart/>                    (d3-scale + curveStepAfter/Before; SVG)
      <TradeTape/>                     (scroll-lock, jump-to-latest, 200-row render cap)
      <PriceChart/>                    (d3-line + volume bars; 10s/1m/5m window)
      <OrderEntry/>                    (limit + market, +/- buttons, cancel col)
      <LatencyMonitor/>                (useFrameMetrics @ 1 Hz via useSyncExternalStore)
      <MetricsPanel/>                  (useThroughput: rates + totals; 2-col grid)
    </Grid>
  </ExchangeProvider>
</App>
```

### Virtualisation policy

`OrderBookLadder` renders ±12 rows around the BBO, which is enough for any realistic-depth
book. When `bids.length + asks.length > 100`, `App` swaps in `VirtualizedLadder`: same row
DOM, but rendered inside a scroll container with absolute positioning and overscan of 8.
Users can also force virtualisation via a header toggle to test the code path against
normal data ([ADR-018](DECISIONS.md#adr-018-frame-rate-instrumentation-via-usesyncexternalstore-virtualisation-by-level-count)).

### Measurement, not just claim

`useFrameMetrics` drives the LatencyMonitor. The frame sampler writes a circular 60-sample
buffer in a module-level array and exposes `subscribe` / `snapshot` via
`useSyncExternalStore`. The component re-renders at 1 Hz (a `setInterval` notifies
subscribers); the sample loop itself is one `requestAnimationFrame` that never calls
`setState`. Frame measurement does not perturb frame budget.

`useThroughput` tracks cumulative counters in refs that update during render (synchronous),
and every second computes deltas / elapsed. The 1-Hz granularity is below what the eye
resolves on rate numbers anyway.

The combination is what makes the "60 fps @ 10 k msg/s" claim in the README a measurement,
not a vibe.

---

## 8. Deployment shapes

- **Dev.** Engine and bridge run natively (macOS or Linux), dashboard via `vite dev` on
  :5173. Hot reload on the dashboard; engine requires a restart to see code changes.
- **Docker Compose.** `engine` and `bridge` share `network_mode: host` on Linux because UDP
  multicast inside Docker's default bridge network is unreliable. `dashboard` is a static
  build served by nginx; it talks to the bridge via the host port.
- **macOS.** Docker Desktop's VM does not forward multicast. Run engine + bridge natively
  and keep `docker compose` for CI.
- **Production.** Out of scope for this project, but the shapes generalise: engine as a
  pinned single-core JVM, bridge as a stateless replica set behind a sticky load balancer
  (one TCP order client per browser session must terminate at the same bridge instance),
  dashboard as a CDN-cached static bundle.
