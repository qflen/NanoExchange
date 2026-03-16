"""Wire-protocol encode/decode round-trip tests.

The Java side of this wire is covered by ``WireCodecTest``. These tests make sure the Python
client agrees with the spec in ``docs/PROTOCOL.md`` on its own, so cross-language drift will
be caught by slice-13's consistency test rather than sneaking through a one-sided encode.
"""

from __future__ import annotations

import struct
import zlib
import pytest

from nanoexchange_client import protocol as p


def test_encode_new_order_frame_layout():
    frame = p.encode_new_order(
        client_id=7, order_id=42, side=p.Side.BUY, order_type=p.OrderType.LIMIT,
        price=100_00000000, quantity=10, display_size=0, timestamp_nanos=12345,
    )
    # length-prefix + (type byte + payload + crc)
    expected_len = p.FRAME_TYPE_SIZE + p.NEW_ORDER_PAYLOAD_SIZE + p.FRAME_CRC_SIZE
    (length,) = struct.unpack_from("<i", frame, 0)
    assert length == expected_len
    assert frame[p.FRAME_HEADER_SIZE] == p.MessageType.NEW_ORDER
    assert len(frame) == p.FRAME_HEADER_SIZE + expected_len


def test_encode_cancel_round_trips_into_java_format():
    frame = p.encode_cancel(order_id=99, timestamp_nanos=1)
    (length,) = struct.unpack_from("<i", frame, 0)
    assert length == p.FRAME_TYPE_SIZE + p.CANCEL_PAYLOAD_SIZE + p.FRAME_CRC_SIZE
    # CRC covers type + payload
    body = frame[p.FRAME_HEADER_SIZE : -p.FRAME_CRC_SIZE]
    (expected_crc,) = struct.unpack_from("<I", frame, len(frame) - p.FRAME_CRC_SIZE)
    assert expected_crc == (zlib.crc32(body) & 0xFFFFFFFF)


def test_encode_heartbeat_is_empty_payload():
    frame = p.encode_heartbeat()
    assert len(frame) == p.FRAME_HEADER_SIZE + p.FRAME_TYPE_SIZE + p.FRAME_CRC_SIZE
    assert frame[p.FRAME_HEADER_SIZE] == p.MessageType.HEARTBEAT


def _synthesize_exec_report(
    *, wire_type: int, order_id: int, client_id: int, side: int, price: int,
    executed_qty: int, executed_price: int, remaining_qty: int, counterparty_id: int,
    reject_reason: int, ts: int,
) -> bytes:
    payload = struct.pack(
        "<qqBqqqqqBq",
        order_id, client_id, side, price,
        executed_qty, executed_price, remaining_qty, counterparty_id,
        reject_reason, ts,
    )
    body = bytes([wire_type]) + payload
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return struct.pack("<i", len(body) + 4) + body + struct.pack("<I", crc)


def test_decode_ack_report():
    buf = _synthesize_exec_report(
        wire_type=p.MessageType.ACK, order_id=1, client_id=5, side=p.Side.BUY,
        price=100_00000000, executed_qty=0, executed_price=0, remaining_qty=10,
        counterparty_id=0, reject_reason=0, ts=42,
    )
    frame, consumed = p.decode_frame(buf)
    assert frame is not None
    assert consumed == len(buf)
    assert frame.type == p.MessageType.ACK
    assert frame.report_type == p.ReportType.ACK
    assert frame.order_id == 1
    assert frame.client_id == 5
    assert frame.remaining_quantity == 10
    assert frame.timestamp_nanos == 42


def test_decode_fill_report():
    buf = _synthesize_exec_report(
        wire_type=p.MessageType.FILL, order_id=2, client_id=9, side=p.Side.SELL,
        price=99_50000000, executed_qty=5, executed_price=99_50000000, remaining_qty=0,
        counterparty_id=77, reject_reason=0, ts=1_000_000,
    )
    frame, _ = p.decode_frame(buf)
    assert frame.report_type == p.ReportType.FILL
    assert frame.executed_quantity == 5
    assert frame.counterparty_order_id == 77
    assert frame.remaining_quantity == 0


def test_decode_rejected_with_reason():
    buf = _synthesize_exec_report(
        wire_type=p.MessageType.REJECTED, order_id=3, client_id=1, side=p.Side.BUY,
        price=0, executed_qty=0, executed_price=0, remaining_qty=0,
        counterparty_id=0, reject_reason=p.RejectReason.SELF_TRADE, ts=0,
    )
    frame, _ = p.decode_frame(buf)
    assert frame.report_type == p.ReportType.REJECTED
    assert frame.reject_reason == p.RejectReason.SELF_TRADE


def test_decode_heartbeat_frame():
    buf = p.encode_heartbeat()
    frame, consumed = p.decode_frame(buf)
    assert frame.type == p.MessageType.HEARTBEAT
    assert consumed == len(buf)


def test_decode_returns_none_on_partial_buffer():
    full = _synthesize_exec_report(
        wire_type=p.MessageType.ACK, order_id=1, client_id=0, side=0, price=0,
        executed_qty=0, executed_price=0, remaining_qty=0, counterparty_id=0,
        reject_reason=0, ts=0,
    )
    frame, consumed = p.decode_frame(full[:10])
    assert frame is None
    assert consumed == 0


def test_decode_rejects_crc_mismatch():
    buf = bytearray(_synthesize_exec_report(
        wire_type=p.MessageType.ACK, order_id=1, client_id=0, side=0, price=0,
        executed_qty=0, executed_price=0, remaining_qty=0, counterparty_id=0,
        reject_reason=0, ts=0,
    ))
    buf[-1] ^= 0xFF
    with pytest.raises(ValueError, match="crc"):
        p.decode_frame(bytes(buf))


def test_decode_rejects_absurd_length():
    bad = struct.pack("<i", 1 << 30) + b"\x00" * 10
    with pytest.raises(ValueError, match="absurd"):
        p.decode_frame(bad)


def test_decode_rejects_inbound_type():
    # NEW_ORDER is outbound-only from client; decoding one should be an error.
    buf = p.encode_new_order(
        client_id=0, order_id=1, side=0, order_type=0, price=1, quantity=1,
    )
    with pytest.raises(ValueError, match="unexpected"):
        p.decode_frame(buf)


def test_two_frames_back_to_back():
    a = _synthesize_exec_report(
        wire_type=p.MessageType.ACK, order_id=1, client_id=0, side=0, price=0,
        executed_qty=0, executed_price=0, remaining_qty=10, counterparty_id=0,
        reject_reason=0, ts=0,
    )
    b = _synthesize_exec_report(
        wire_type=p.MessageType.FILL, order_id=1, client_id=0, side=0, price=100,
        executed_qty=10, executed_price=100, remaining_qty=0, counterparty_id=2,
        reject_reason=0, ts=0,
    )
    buf = a + b
    f1, c1 = p.decode_frame(buf)
    assert f1.type == p.MessageType.ACK
    f2, c2 = p.decode_frame(buf[c1:])
    assert f2.type == p.MessageType.FILL
    assert c1 + c2 == len(buf)
