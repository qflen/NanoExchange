"""Journal round-trip tests.

The on-disk format is locked down by ``docs/PROTOCOL.md`` §1. We write
synthetic journals with :class:`JournalWriter` and read them back with
:class:`Journal`. A separate test asserts that corruption at the tail
terminates iteration cleanly (rather than raising).
"""

import struct

from nanoexchange_analytics.journal_reader import (
    HEADER_BYTES,
    Journal,
    JournalWriter,
)


def test_writer_reader_round_trip(tmp_path):
    path = tmp_path / "j1.bin"
    with JournalWriter(path) as w:
        w.append(1, b"hello")
        w.append(2, b"\x01\x02\x03\x04")
        w.append(3, b"" * 0 + b"last")
    records = Journal(path).read_all()
    assert [(r.sequence, r.payload) for r in records] == [
        (1, b"hello"),
        (2, b"\x01\x02\x03\x04"),
        (3, b"last"),
    ]


def test_preallocated_tail_terminates_cleanly(tmp_path):
    """A Java-produced journal is a zero-filled mmap; the reader must
    stop at the first length=0 record without raising."""
    path = tmp_path / "j2.bin"
    with JournalWriter(path, preallocate=1024) as w:
        w.append(1, b"only-one")
    # File is 1024 bytes; after the one record the tail is all zeros.
    records = Journal(path).read_all()
    assert [(r.sequence, r.payload) for r in records] == [(1, b"only-one")]


def test_crc_mismatch_at_tail_terminates(tmp_path):
    """Flip a byte inside the payload of the second record. Reader must
    deliver record #1 and stop — not raise and not skip ahead."""
    path = tmp_path / "j3.bin"
    with JournalWriter(path) as w:
        w.append(1, b"first-good-record")
        w.append(2, b"second-will-be-corrupted")

    raw = path.read_bytes()
    # Layout: 12 header + 17 payload + 4 crc = 33 bytes for record 1.
    # Record 2 header starts at 33. Its payload starts at 33+12 = 45.
    # Flip one byte.
    i = 45 + 2
    corrupted = bytearray(raw)
    corrupted[i] ^= 0xFF
    path.write_bytes(bytes(corrupted))

    records = Journal(path).read_all()
    assert [r.sequence for r in records] == [1]


def test_torn_write_truncated_record(tmp_path):
    """Cut the file off mid-record: reader sees the first record and
    then cleanly returns (no partial-read exception)."""
    path = tmp_path / "j4.bin"
    with JournalWriter(path) as w:
        w.append(1, b"A")
        w.append(2, b"BB")
    raw = path.read_bytes()
    # Truncate mid-record-2 (before its CRC).
    path.write_bytes(raw[: HEADER_BYTES + 1 + 4 + HEADER_BYTES + 1])
    assert [r.sequence for r in Journal(path).read_all()] == [1]


def test_negative_length_header_is_end_of_log(tmp_path):
    """A ``length < 0`` record is not a valid frame — reader stops."""
    path = tmp_path / "j5.bin"
    with JournalWriter(path) as w:
        w.append(1, b"ok")
    # Append a bogus "length = -1" header.
    with path.open("ab") as f:
        f.write(struct.pack("<qi", 99, -1))
    assert [r.sequence for r in Journal(path).read_all()] == [1]
