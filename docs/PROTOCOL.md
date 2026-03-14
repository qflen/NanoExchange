# Wire and Storage Protocols

This document defines byte-level formats for everything NanoExchange writes to a socket or a file. Consumers, replayers, and test fixtures read this document — not the Java code — as the source of truth.

All multi-byte integers are **little-endian**. All fixed-point prices are stored as `int64` representing `price × 10⁸` (see DECISIONS.md ADR-001).

---

## 1. Journal record format (slice 3)

The journal is an append-only binary log mapped into memory from a single file. Every engine input event (new order, cancel, modify) and every emitted execution report is written here as one record. Replaying the file into a fresh engine reproduces the output stream byte-for-byte, which is what makes the engine testable and auditable.

### Record layout

```
 offset  size  field                             notes
 ------  ----  --------------------------------  -----------------------------
      0     8  sequence                          int64, LE, monotonic
      8     4  length (N)                        int32, LE, payload bytes
     12     N  payload                           opaque to journal
   12+N     4  crc32                             int32, LE, over bytes [0, 12+N)
```

**Total record size:** `16 + N` bytes. The fixed overhead is 16 bytes (8 + 4 + 4).

### Field semantics

- **sequence** — a monotonic int64 assigned by the writer. The journal itself does not enforce monotonicity; it is a caller contract. Replay passes each record's sequence to the visitor so downstream code can detect gaps if it cares.
- **length** — the exact number of payload bytes that follow the header. A length of `0` (or negative) is treated as end-of-log by the reader. The mapped file is pre-allocated and zero-filled at creation, so the natural tail of unused bytes reads as length=0 and terminates replay cleanly without needing a trailer sentinel.
- **payload** — opaque bytes. The journal does not interpret them; slice 4 will define how order/cancel/modify/report messages serialize into this envelope.
- **crc32** — standard CRC-32 (polynomial 0xEDB88320, as implemented by `java.util.zip.CRC32`) computed over bytes `[0, 12+N)` of the record — that is, the sequence, length, and payload, but *not* the CRC field itself. Used to detect torn writes and partial flushes at the tail.

### Example: one record with a 5-byte payload

Sequence `1`, payload `AA BB CC DD EE`:

```
offset  hex                                            meaning
 0x00   01 00 00 00 00 00 00 00                        sequence = 1
 0x08   05 00 00 00                                    length = 5
 0x0C   AA BB CC DD EE                                 payload
 0x11   XX XX XX XX                                    crc32 over [0x00, 0x11)
```

Total on disk: 21 bytes (16 overhead + 5 payload).

### End-of-log detection

Replay stops — without raising — on the first record where any of these hold:

1. The length field is `≤ 0`.
2. The trailer would extend past the end of the file (`offset + 12 + N + 4 > fileSize`).
3. The CRC does not match.

This makes the format resilient to two failure modes:
- **Tail of unused space** in a fresh or oversize journal file. Zeros everywhere → length=0 → clean stop.
- **Torn write** mid-record (process killed between header and trailer flush). CRC will not verify → clean stop. The partial record is silently discarded; the next clean startup can overwrite it.

### Append semantics

`append(seq, payload)` writes the record at the current write offset and advances the offset by `16 + N`. It throws `IllegalStateException` if the remaining mapped region cannot hold the record — callers are expected to size the journal generously for the expected session duration.

`force()` calls `MappedByteBuffer.force()`, which asks the OS to flush dirty pages to the backing device. It is expensive (can stall on the order of milliseconds on a spinning disk; tens of microseconds to a few hundred microseconds on NVMe) and must not be called per-record on the hot path. The engine forces periodically (batch boundary) or on graceful shutdown.

### Reopen semantics

Opening a journal that already contains records scans forward from offset 0, validating each record by CRC, until one fails the end-of-log criteria above. The write offset is positioned at the first byte past the last valid record, so further appends continue the log without damaging prior data. This is what makes restart-safe crash recovery possible.

### Concurrency

Writes are from a single thread (the engine). `replay` opens an independent read-only view of the file, so it can run concurrently against a live writer — though in this codebase we only replay offline, for restart or determinism tests.

---

## 2. Order-entry wire protocol (slice 4)

Clients connect to the order gateway over TCP and exchange length-prefixed binary frames. The transport is TCP with `TCP_NODELAY` enabled on both sides (Nagle's algorithm is disabled because it would coalesce small order frames and add up to 200 ms of latency waiting for ACKs — unacceptable for order entry).

### Framing

```
 offset  size  field
 ------  ----  ---------------------------------------
      0     4  length (LE int32) — bytes *after* this field (type + payload + crc)
      4     1  type (see type table below)
      5     N  payload (variable, per type)
  5 + N     4  crc32 (LE int32) — over bytes [4, 5+N)
```

Total frame size is `8 + N` bytes. CRC covers the type byte and payload — not the length prefix and not the CRC itself. That way the CRC is content-addressed: moving the same message into a new framing envelope yields the same CRC.

Length-prefix framing is used instead of a delimiter because payloads contain arbitrary binary data; any delimiter byte will eventually appear inside a valid payload.

### Message types

| value  | name          | direction        |
|--------|---------------|------------------|
| 0x01   | NEW_ORDER     | client → engine  |
| 0x02   | CANCEL        | client → engine  |
| 0x03   | MODIFY        | client → engine  |
| 0x20   | ACK           | engine → client  |
| 0x21   | PARTIAL_FILL  | engine → client  |
| 0x22   | FILL          | engine → client  |
| 0x23   | CANCELED      | engine → client  |
| 0x24   | REJECTED      | engine → client  |
| 0x25   | MODIFIED      | engine → client  |
| 0x40   | HEARTBEAT     | either direction |

Inbound values are in `0x00–0x1F`, outbound in `0x20–0x3F`, control (heartbeat, etc.) in `0x40–0x4F`. New values never reuse old slots — byte constants are part of the compatibility contract.

### NEW_ORDER payload (50 bytes)

| offset | size | field            | notes                                   |
|--------|------|------------------|-----------------------------------------|
|      0 |    8 | clientId         | advisory on the wire; gateway overrides from the authenticated session id |
|      8 |    8 | orderId          | client-assigned, must be unique per session |
|     16 |    1 | side             | 0 = BUY, 1 = SELL                        |
|     17 |    1 | orderType        | 0 = LIMIT, 1 = MARKET, 2 = IOC, 3 = FOK, 4 = ICEBERG |
|     18 |    8 | price            | fixed-point (price × 10⁸); ignored for MARKET |
|     26 |    8 | quantity         | positive                                 |
|     34 |    8 | displaySize      | iceberg tip size; 0 for non-iceberg      |
|     42 |    8 | timestampNanos   | client-nominated nanoseconds             |

### CANCEL payload (16 bytes)

| offset | size | field          |
|--------|------|----------------|
|      0 |    8 | orderId        |
|      8 |    8 | timestampNanos |

### MODIFY payload (32 bytes)

| offset | size | field          |
|--------|------|----------------|
|      0 |    8 | orderId        |
|      8 |    8 | newPrice       |
|     16 |    8 | newQuantity    |
|     24 |    8 | timestampNanos |

### Execution-report payload (66 bytes) — shared by ACK, PARTIAL_FILL, FILL, CANCELED, REJECTED, MODIFIED

The report *kind* is conveyed by the frame type byte; the payload does not repeat it.

| offset | size | field                | notes                                |
|--------|------|----------------------|--------------------------------------|
|      0 |    8 | orderId              |                                      |
|      8 |    8 | clientId             |                                      |
|     16 |    1 | side                 |                                      |
|     17 |    8 | price                | the order's resting price            |
|     25 |    8 | executedQuantity     | this fill's size; 0 for ACK/CANCELED |
|     33 |    8 | executedPrice        | this fill's price; 0 for ACK         |
|     41 |    8 | remainingQuantity    | after this event                     |
|     49 |    8 | counterpartyOrderId  | the maker on a FILL / PARTIAL        |
|     57 |    1 | rejectReason         | 0 when not REJECTED; see engine `ExecutionReport` constants |
|     58 |    8 | timestampNanos       |                                      |

### HEARTBEAT payload (0 bytes)

Just the type byte + CRC. Either side may send; the counterparty is expected to ignore-or-respond within its own policy.

### Example: NEW_ORDER frame (hex)

A NEW_ORDER for clientId=0x42, orderId=0x01, BUY, LIMIT, price=100.00 (10_000_000_000), qty=10, displaySize=0, ts=0:

```
04 00 00 00 | 32 00 00 00 length = 50 + 1 (type) + 4 (crc) = 55? no — see below
```

Worked example (actual bytes):

```
offset  hex                                                meaning
 0x00   37 00 00 00                                        length = 55 (type + payload + crc)
 0x04   01                                                 type = NEW_ORDER
 0x05   42 00 00 00 00 00 00 00                            clientId = 0x42
 0x0D   01 00 00 00 00 00 00 00                            orderId = 1
 0x15   00                                                 side = BUY
 0x16   00                                                 orderType = LIMIT
 0x17   00 E4 0B 54 02 00 00 00                            price = 10_000_000_000
 0x1F   0A 00 00 00 00 00 00 00                            quantity = 10
 0x27   00 00 00 00 00 00 00 00                            displaySize = 0
 0x2F   00 00 00 00 00 00 00 00                            timestampNanos = 0
 0x37   XX XX XX XX                                        crc32 over bytes [0x04, 0x37)
```

Total on wire: 59 bytes (4 length + 55 body-and-crc). That matches `8 + NEW_ORDER_PAYLOAD_SIZE(50) + 1 = 59`.

### Error handling

- A length that is less than 5 (type+crc) or greater than 1 MiB is treated as corruption; the gateway closes the connection.
- A CRC mismatch closes the connection.
- An unknown type byte closes the connection.
- Partial bytes (length prefix or body short of the declared length) do not close the connection; they simply wait for more.
- Malformed frames trigger `InboundHandler.onProtocolError` followed by `onDisconnect`.

---

*Slice 5 will extend this document with the UDP multicast market-data feed (snapshot + incremental).*
