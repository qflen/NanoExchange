"""Market-data feed parser, mirrored from the Java ``FeedCodec``.

Datagrams are self-contained; unlike the TCP order-entry protocol there is no framing for
partial reads. One UDP packet = one decoded message.
"""

from __future__ import annotations

import enum
import struct
import zlib
from dataclasses import dataclass


class FeedMessageType(enum.IntEnum):
    BOOK_UPDATE = 0x01
    TRADE       = 0x02
    SNAPSHOT    = 0x03
    HEARTBEAT   = 0x04


_HEADER          = struct.Struct("<qB")           # seq, type
_CRC             = struct.Struct("<I")
_BOOK_UPDATE     = struct.Struct("<Bqqi")         # side, price, qty, orderCount
_TRADE           = struct.Struct("<qqBqqq")      # takerId, makerId, side, price, qty, ts
_SNAPSHOT_HEADER = struct.Struct("<qhhh")        # startingSeq, part, totalParts, levelCount
_SNAPSHOT_LEVEL  = struct.Struct("<Bqqi")        # same as book update


@dataclass(frozen=True)
class BookUpdate:
    seq: int
    side: int
    price: int
    quantity: int
    order_count: int


@dataclass(frozen=True)
class Trade:
    seq: int
    taker_order_id: int
    maker_order_id: int
    taker_side: int
    price: int
    quantity: int
    timestamp_nanos: int


@dataclass(frozen=True)
class Heartbeat:
    seq: int


@dataclass(frozen=True)
class SnapshotLevel:
    side: int
    price: int
    quantity: int
    order_count: int


@dataclass(frozen=True)
class Snapshot:
    seq: int
    starting_sequence: int
    part_number: int
    total_parts: int
    levels: list[SnapshotLevel]


def decode_datagram(data: bytes):
    """Decode one UDP datagram into a message object.

    Raises ``ValueError`` on bad CRC, unknown type, or truncated data.
    """
    if len(data) < _HEADER.size + _CRC.size:
        raise ValueError("datagram too small")
    seq, type_byte = _HEADER.unpack_from(data, 0)
    crc_pos = len(data) - _CRC.size
    (expected_crc,) = _CRC.unpack_from(data, crc_pos)
    actual_crc = zlib.crc32(data[:crc_pos]) & 0xFFFFFFFF
    if expected_crc != actual_crc:
        raise ValueError("feed crc mismatch")

    payload_start = _HEADER.size
    payload_end = crc_pos
    payload = data[payload_start:payload_end]

    if type_byte == FeedMessageType.BOOK_UPDATE:
        side, price, qty, count = _BOOK_UPDATE.unpack(payload)
        return BookUpdate(seq=seq, side=side, price=price, quantity=qty, order_count=count)
    if type_byte == FeedMessageType.TRADE:
        taker, maker, side, price, qty, ts = _TRADE.unpack(payload)
        return Trade(
            seq=seq, taker_order_id=taker, maker_order_id=maker, taker_side=side,
            price=price, quantity=qty, timestamp_nanos=ts,
        )
    if type_byte == FeedMessageType.HEARTBEAT:
        return Heartbeat(seq=seq)
    if type_byte == FeedMessageType.SNAPSHOT:
        starting, part, total, count = _SNAPSHOT_HEADER.unpack_from(payload, 0)
        levels: list[SnapshotLevel] = []
        off = _SNAPSHOT_HEADER.size
        for _ in range(count):
            side, price, qty, oc = _SNAPSHOT_LEVEL.unpack_from(payload, off)
            off += _SNAPSHOT_LEVEL.size
            levels.append(SnapshotLevel(side=side, price=price, quantity=qty, order_count=oc))
        return Snapshot(
            seq=seq, starting_sequence=starting,
            part_number=part, total_parts=total, levels=levels,
        )
    raise ValueError(f"unknown feed message type 0x{type_byte:02X}")
