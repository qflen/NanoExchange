"""Binary wire protocol, mirrored from the Java ``WireCodec``.

The single source of truth for message layouts is ``docs/PROTOCOL.md``. This file must stay
byte-compatible with the Java side; a cross-language consistency test (slice 13) generates
expected frames from both and compares them.

Frame layout (little-endian everywhere):

    length: int32  -- bytes after this field (type + payload + crc)
    type:   int8
    payload: bytes
    crc32:  int32  -- over type + payload
"""

from __future__ import annotations

import enum
import struct
import zlib
from dataclasses import dataclass
from typing import Optional


FRAME_HEADER_SIZE = 4  # length prefix
FRAME_CRC_SIZE = 4
FRAME_TYPE_SIZE = 1


class MessageType(enum.IntEnum):
    NEW_ORDER    = 0x01
    CANCEL       = 0x02
    MODIFY       = 0x03
    ACK          = 0x20
    PARTIAL_FILL = 0x21
    FILL         = 0x22
    CANCELED     = 0x23
    REJECTED     = 0x24
    MODIFIED     = 0x25
    HEARTBEAT    = 0x40


class Side(enum.IntEnum):
    BUY  = 0
    SELL = 1


class OrderType(enum.IntEnum):
    LIMIT   = 0
    MARKET  = 1
    IOC     = 2
    FOK     = 3
    ICEBERG = 4


class ReportType(enum.IntEnum):
    ACK          = 0
    PARTIAL_FILL = 1
    FILL         = 2
    CANCELED     = 3
    REJECTED     = 4
    MODIFIED     = 5


class RejectReason(enum.IntEnum):
    NONE              = 0
    SELF_TRADE        = 1
    FOK_UNFILLABLE    = 2
    UNKNOWN_ORDER     = 3
    NO_LIQUIDITY      = 4
    MODIFY_UNKNOWN    = 5


# --- struct formats -----------------------------------------------------------
# Payload-only (type byte and framing handled separately).
_NEW_ORDER_PAYLOAD = struct.Struct("<qqBBqqqq")   # clientId, orderId, side, type, price, qty, displaySize, ts
_CANCEL_PAYLOAD    = struct.Struct("<qq")         # orderId, ts
_MODIFY_PAYLOAD    = struct.Struct("<qqqq")       # orderId, newPrice, newQty, ts
_EXEC_REPORT_PAYLOAD = struct.Struct("<qqBqqqqqBq")
# orderId, clientId, side, price, executedQty, executedPrice, remainingQty, counterpartyOrderId, rejectReason, ts

NEW_ORDER_PAYLOAD_SIZE = _NEW_ORDER_PAYLOAD.size
CANCEL_PAYLOAD_SIZE    = _CANCEL_PAYLOAD.size
MODIFY_PAYLOAD_SIZE    = _MODIFY_PAYLOAD.size
EXEC_REPORT_PAYLOAD_SIZE = _EXEC_REPORT_PAYLOAD.size


# --- encoders -----------------------------------------------------------------

def _wrap_frame(type_byte: int, payload: bytes) -> bytes:
    body = bytes([type_byte]) + payload
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return (
        struct.pack("<i", len(body) + FRAME_CRC_SIZE)
        + body
        + struct.pack("<I", crc)
    )


def encode_new_order(
    *, client_id: int, order_id: int, side: int, order_type: int,
    price: int, quantity: int, display_size: int = 0, timestamp_nanos: int = 0,
) -> bytes:
    payload = _NEW_ORDER_PAYLOAD.pack(
        client_id, order_id, side, order_type, price, quantity, display_size, timestamp_nanos
    )
    return _wrap_frame(MessageType.NEW_ORDER, payload)


def encode_cancel(*, order_id: int, timestamp_nanos: int = 0) -> bytes:
    return _wrap_frame(MessageType.CANCEL, _CANCEL_PAYLOAD.pack(order_id, timestamp_nanos))


def encode_modify(
    *, order_id: int, new_price: int, new_quantity: int, timestamp_nanos: int = 0,
) -> bytes:
    return _wrap_frame(
        MessageType.MODIFY,
        _MODIFY_PAYLOAD.pack(order_id, new_price, new_quantity, timestamp_nanos),
    )


def encode_heartbeat() -> bytes:
    return _wrap_frame(MessageType.HEARTBEAT, b"")


# --- decoder ------------------------------------------------------------------

@dataclass(frozen=True)
class DecodedFrame:
    """One decoded wire frame. Populated union of fields — unused fields are 0/None."""
    type: int
    # Common execution-report fields:
    report_type: Optional[int] = None
    order_id: int = 0
    client_id: int = 0
    side: int = 0
    price: int = 0
    executed_quantity: int = 0
    executed_price: int = 0
    remaining_quantity: int = 0
    counterparty_order_id: int = 0
    reject_reason: int = 0
    timestamp_nanos: int = 0


def decode_frame(buf: bytes) -> tuple[Optional[DecodedFrame], int]:
    """Decode a single frame from the front of ``buf``.

    Returns ``(frame_or_None, bytes_consumed)``. If the buffer does not contain a complete
    frame yet, returns ``(None, 0)``. On CRC / framing error, raises ``ValueError``.
    """
    if len(buf) < FRAME_HEADER_SIZE:
        return None, 0
    (length,) = struct.unpack_from("<i", buf, 0)
    if length < FRAME_TYPE_SIZE + FRAME_CRC_SIZE or length > 1 << 20:
        raise ValueError(f"absurd frame length {length}")
    total = FRAME_HEADER_SIZE + length
    if len(buf) < total:
        return None, 0

    body = buf[FRAME_HEADER_SIZE : FRAME_HEADER_SIZE + length - FRAME_CRC_SIZE]
    (expected_crc,) = struct.unpack_from("<I", buf, FRAME_HEADER_SIZE + length - FRAME_CRC_SIZE)
    actual_crc = zlib.crc32(body) & 0xFFFFFFFF
    if expected_crc != actual_crc:
        raise ValueError("crc mismatch")

    type_byte = body[0]
    payload = body[1:]

    if type_byte in (
        MessageType.ACK, MessageType.PARTIAL_FILL, MessageType.FILL,
        MessageType.CANCELED, MessageType.REJECTED, MessageType.MODIFIED,
    ):
        if len(payload) != EXEC_REPORT_PAYLOAD_SIZE:
            raise ValueError("bad exec-report payload size")
        (
            order_id, client_id, side, price,
            executed_qty, executed_price, remaining_qty,
            counterparty_id, reject_reason, ts,
        ) = _EXEC_REPORT_PAYLOAD.unpack(payload)
        return DecodedFrame(
            type=type_byte,
            report_type=_wire_to_report_type(type_byte),
            order_id=order_id, client_id=client_id, side=side, price=price,
            executed_quantity=executed_qty, executed_price=executed_price,
            remaining_quantity=remaining_qty, counterparty_order_id=counterparty_id,
            reject_reason=reject_reason, timestamp_nanos=ts,
        ), total
    if type_byte == MessageType.HEARTBEAT:
        if payload:
            raise ValueError("heartbeat must have empty payload")
        return DecodedFrame(type=type_byte), total

    # Inbound frames (NEW_ORDER / CANCEL / MODIFY) are not decoded on the client side; a
    # client only needs to encode them to send. Treat as unknown here.
    raise ValueError(f"unexpected frame type 0x{type_byte:02X}")


def _wire_to_report_type(wire_type: int) -> int:
    return {
        MessageType.ACK:          ReportType.ACK,
        MessageType.PARTIAL_FILL: ReportType.PARTIAL_FILL,
        MessageType.FILL:         ReportType.FILL,
        MessageType.CANCELED:     ReportType.CANCELED,
        MessageType.REJECTED:     ReportType.REJECTED,
        MessageType.MODIFIED:     ReportType.MODIFIED,
    }[wire_type]
