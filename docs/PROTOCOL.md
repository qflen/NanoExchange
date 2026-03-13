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

*Slices 4 and later will extend this document with the wire protocol (binary frames over TCP), the market-data feed format (UDP multicast with snapshot + incremental), and any further on-disk formats.*
