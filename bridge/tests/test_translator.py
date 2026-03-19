"""
Translator tests: build a datagram on the Java wire format, decode it
with the client library, and assert the JSON shape the bridge emits.
"""

import struct
import zlib

from nanoexchange_client.feed import decode_datagram
from nanoexchange_client.protocol import (
    DecodedFrame,
    MessageType,
    ReportType,
)
from nanoexchange_bridge.translator import (
    order_from_json,
    translate_exec_report,
    translate_feed_message,
)


def _wrap_feed(seq: int, type_byte: int, payload: bytes) -> bytes:
    header = struct.pack("<qB", seq, type_byte)
    body = header + payload
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return body + struct.pack("<I", crc)


def test_book_update_to_json():
    payload = struct.pack("<Bqqi", 0, 100_00000000, 250, 3)  # side=BUY, $100.00, qty 250, 3 orders
    datagram = _wrap_feed(seq=7, type_byte=0x01, payload=payload)
    obj = decode_datagram(datagram)
    out = translate_feed_message(obj)
    assert out == {
        "type": "book_update",
        "seq": 7,
        "side": "BUY",
        "price": 100.0,
        "quantity": 250,
        "order_count": 3,
    }


def test_trade_to_json():
    payload = struct.pack("<qqBqqq", 7, 8, 1, 100_25000000, 5, 999)
    datagram = _wrap_feed(seq=42, type_byte=0x02, payload=payload)
    obj = decode_datagram(datagram)
    out = translate_feed_message(obj)
    assert out == {
        "type": "trade",
        "seq": 42,
        "taker_order_id": 7,
        "maker_order_id": 8,
        "taker_side": "SELL",
        "price": 100.25,
        "quantity": 5,
        "ts_ns": 999,
    }


def test_heartbeat_is_dropped():
    datagram = _wrap_feed(seq=1, type_byte=0x04, payload=b"")
    assert translate_feed_message(decode_datagram(datagram)) is None


def test_exec_report_to_json():
    frame = DecodedFrame(
        type=MessageType.PARTIAL_FILL,
        report_type=ReportType.PARTIAL_FILL,
        order_id=100,
        client_id=7,
        side=0,
        price=100_00000000,
        executed_price=100_00000000,
        executed_quantity=5,
        remaining_quantity=5,
        counterparty_order_id=42,
        timestamp_nanos=12345,
    )
    out = translate_exec_report(frame)
    assert out == {
        "type": "exec_report",
        "report_type": "PARTIAL_FILL",
        "order_id": 100,
        "client_id": 7,
        "side": "BUY",
        "price": 100.0,
        "executed_price": 100.0,
        "executed_quantity": 5,
        "remaining_quantity": 5,
        "counterparty_order_id": 42,
        "ts_ns": 12345,
    }


def test_heartbeat_frame_is_dropped():
    frame = DecodedFrame(type=MessageType.HEARTBEAT)
    assert translate_exec_report(frame) is None


def test_order_from_json_scales_price():
    out = order_from_json({
        "type": "new_order",
        "client_id": 7,
        "order_id": 42,
        "side": "SELL",
        "order_type": "LIMIT",
        "price": 100.25,
        "quantity": 10,
    })
    assert out["price"] == 100_25000000
    assert out["side"] == 1
    assert out["order_type"] == 0
    assert out["quantity"] == 10


def test_order_from_json_market_has_zero_price():
    out = order_from_json({
        "type": "new_order",
        "order_id": 1,
        "side": "BUY",
        "order_type": "MARKET",
        "quantity": 5,
    })
    assert out["price"] == 0
    assert out["order_type"] == 1
