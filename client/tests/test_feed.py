"""Feed-datagram decode tests.

We hand-build bytes in the exact layout the Java ``FeedCodec`` emits to make sure the Python
decoder stays byte-compatible.
"""

from __future__ import annotations

import struct
import zlib
import pytest

from nanoexchange_client import feed


def _wrap_feed(seq: int, type_byte: int, payload: bytes) -> bytes:
    header = struct.pack("<qB", seq, type_byte)
    body = header + payload
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return body + struct.pack("<I", crc)


def test_decode_book_update():
    payload = struct.pack("<Bqqi", 0, 100_00000000, 50, 3)
    data = _wrap_feed(seq=1, type_byte=feed.FeedMessageType.BOOK_UPDATE, payload=payload)
    msg = feed.decode_datagram(data)
    assert isinstance(msg, feed.BookUpdate)
    assert msg.seq == 1
    assert msg.side == 0
    assert msg.price == 100_00000000
    assert msg.quantity == 50
    assert msg.order_count == 3


def test_decode_trade():
    payload = struct.pack("<qqBqqq", 10, 20, 1, 99_50000000, 5, 1_000_000)
    data = _wrap_feed(seq=2, type_byte=feed.FeedMessageType.TRADE, payload=payload)
    msg = feed.decode_datagram(data)
    assert isinstance(msg, feed.Trade)
    assert msg.taker_order_id == 10
    assert msg.maker_order_id == 20
    assert msg.taker_side == 1
    assert msg.price == 99_50000000
    assert msg.quantity == 5


def test_decode_heartbeat():
    data = _wrap_feed(seq=7, type_byte=feed.FeedMessageType.HEARTBEAT, payload=b"")
    msg = feed.decode_datagram(data)
    assert isinstance(msg, feed.Heartbeat)
    assert msg.seq == 7


def test_decode_snapshot_with_levels():
    header = struct.pack("<qhhh", 100, 1, 1, 2)  # startingSeq, part, totalParts, count
    l1 = struct.pack("<Bqqi", 0, 100, 10, 1)
    l2 = struct.pack("<Bqqi", 1, 101, 20, 2)
    data = _wrap_feed(seq=5, type_byte=feed.FeedMessageType.SNAPSHOT, payload=header + l1 + l2)
    msg = feed.decode_datagram(data)
    assert isinstance(msg, feed.Snapshot)
    assert msg.starting_sequence == 100
    assert msg.part_number == 1
    assert msg.total_parts == 1
    assert len(msg.levels) == 2
    assert msg.levels[0].price == 100 and msg.levels[0].side == 0
    assert msg.levels[1].price == 101 and msg.levels[1].side == 1


def test_decode_rejects_crc_mismatch():
    data = bytearray(_wrap_feed(seq=1, type_byte=feed.FeedMessageType.HEARTBEAT, payload=b""))
    data[-1] ^= 0xFF
    with pytest.raises(ValueError, match="crc"):
        feed.decode_datagram(bytes(data))


def test_decode_rejects_unknown_type():
    data = _wrap_feed(seq=1, type_byte=0x7F, payload=b"")
    with pytest.raises(ValueError, match="unknown"):
        feed.decode_datagram(data)


def test_decode_rejects_tiny():
    with pytest.raises(ValueError):
        feed.decode_datagram(b"\x00\x01")
