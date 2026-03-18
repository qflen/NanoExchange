"""
Reader (and writer) for the NanoExchange binary journal.

The journal format is defined in ``docs/PROTOCOL.md`` §1 and produced by
``com.nanoexchange.engine.Journal`` on the Java side. Each record is::

    offset  size  field
      0       8   sequence            int64 LE
      8       4   length (N)          int32 LE
     12       N   payload             opaque
   12+N       4   crc32               int32 LE, over bytes [0, 12+N)

End-of-log is signalled by any of: length ≤ 0, a trailer that would run
past the file, or a CRC mismatch. All three are expected at the tail of a
pre-allocated, zero-filled journal — they terminate iteration cleanly.

The CRC polynomial is the reflected CRC-32 implemented by
``java.util.zip.CRC32`` and Python's ``binascii.crc32`` (they are the same
polynomial, 0xEDB88320).
"""

from __future__ import annotations

import binascii
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterator

_HEADER = struct.Struct("<qi")      # sequence, length
_TRAILER = struct.Struct("<i")      # crc32 (stored as signed int32 to match Java)
HEADER_BYTES = 12
TRAILER_BYTES = 4
RECORD_OVERHEAD = HEADER_BYTES + TRAILER_BYTES


@dataclass(frozen=True, slots=True)
class JournalRecord:
    sequence: int
    payload: bytes


class Journal:
    """Iterate a journal file, yielding one :class:`JournalRecord` per intact record."""

    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)

    def __iter__(self) -> Iterator[JournalRecord]:
        with self._path.open("rb") as f:
            yield from _iter_records(f)

    def read_all(self) -> list[JournalRecord]:
        return list(self)


def _iter_records(stream: BinaryIO) -> Iterator[JournalRecord]:
    while True:
        header = stream.read(HEADER_BYTES)
        if len(header) < HEADER_BYTES:
            return
        seq, length = _HEADER.unpack(header)
        if length <= 0:
            return
        payload = stream.read(length)
        if len(payload) < length:
            return
        trailer = stream.read(TRAILER_BYTES)
        if len(trailer) < TRAILER_BYTES:
            return
        expected = _TRAILER.unpack(trailer)[0] & 0xFFFFFFFF
        computed = binascii.crc32(header + payload) & 0xFFFFFFFF
        if expected != computed:
            return
        yield JournalRecord(sequence=seq, payload=payload)


class JournalWriter:
    """Append journal records in the Java-compatible format.

    Used by tests and by the simulator when it wants to produce a journal
    that the Python reader (and the Java ``Journal.replay`` static method)
    can both consume. The writer does not memory-map like the Java side —
    it just appends to a regular file — but the on-disk bytes are
    byte-identical to what Java produces.
    """

    def __init__(self, path: str | Path, *, preallocate: int = 0) -> None:
        self._path = Path(path)
        self._file = self._path.open("wb")
        if preallocate > 0:
            # Zero-fill so the tail behaves like a Java-allocated journal. Useful when the
            # file is going to be read by the Java replay (which stops on length=0).
            self._file.truncate(preallocate)

    def append(self, sequence: int, payload: bytes) -> None:
        header = _HEADER.pack(sequence, len(payload))
        crc = binascii.crc32(header + payload) & 0xFFFFFFFF
        # Java stores the CRC via putInt, which writes the low 32 bits as signed.
        # Re-interpret the unsigned CRC as signed to match byte-for-byte.
        signed = crc if crc < 0x80000000 else crc - 0x100000000
        self._file.write(header)
        self._file.write(payload)
        self._file.write(_TRAILER.pack(signed))

    def close(self) -> None:
        if not self._file.closed:
            self._file.flush()
            self._file.close()

    def __enter__(self) -> "JournalWriter":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()
