"""
Binary ↔ JSON translation for the WebSocket bridge.

Inputs are the decoded objects from :mod:`nanoexchange_client.feed`
(dataclasses) and :mod:`nanoexchange_client.protocol` (``DecodedFrame``).
This module only maps them to dicts and back. The JSON shape is
intentionally small and UI-shaped: prices in dollars as floating-point,
timestamps in milliseconds or nanoseconds, sides as strings. The
binary-side representation (int64 fixed-point, nanoseconds) stays on
the exchange side — that is exactly what a bridge is for.

The reverse direction — a browser placing an order — is also handled
here: :func:`order_from_json` parses a WS JSON payload and returns the
integer tuple the existing :class:`~nanoexchange_client.OrderClient`
methods expect.
"""

from __future__ import annotations

from typing import Any, Mapping

from nanoexchange_client.feed import (
    BookUpdate,
    Heartbeat,
    Snapshot,
    Trade as FeedTrade,
)
from nanoexchange_client.protocol import DecodedFrame, MessageType, ReportType

# See PROTOCOL.md §2 — prices are int64 * 10^8.
PRICE_SCALE = 100_000_000
SIDE_LABELS = {0: "BUY", 1: "SELL"}
SIDE_BY_LABEL = {"BUY": 0, "SELL": 1}
_REPORT_LABELS = {
    ReportType.ACK: "ACK",
    ReportType.PARTIAL_FILL: "PARTIAL_FILL",
    ReportType.FILL: "FILL",
    ReportType.CANCELED: "CANCELED",
    ReportType.REJECTED: "REJECTED",
    ReportType.MODIFIED: "MODIFIED",
}


def translate_feed_message(msg: Any) -> dict[str, Any] | None:
    """Translate a decoded feed message to a JSON-shaped dict. Returns
    ``None`` for heartbeats — the bridge doesn't flood websockets with
    keep-alive noise (the WS protocol has its own keepalive)."""
    if isinstance(msg, BookUpdate):
        return {
            "type": "book_update",
            "seq": msg.seq,
            "side": SIDE_LABELS[msg.side],
            "price": msg.price / PRICE_SCALE,
            "quantity": msg.quantity,
            "order_count": msg.order_count,
        }
    if isinstance(msg, FeedTrade):
        return {
            "type": "trade",
            "seq": msg.seq,
            "taker_order_id": msg.taker_order_id,
            "maker_order_id": msg.maker_order_id,
            "taker_side": SIDE_LABELS[msg.taker_side],
            "price": msg.price / PRICE_SCALE,
            "quantity": msg.quantity,
            "ts_ns": msg.timestamp_nanos,
        }
    if isinstance(msg, Snapshot):
        return {
            "type": "snapshot",
            "seq": msg.seq,
            "starting_sequence": msg.starting_sequence,
            "part_number": msg.part_number,
            "total_parts": msg.total_parts,
            "levels": [
                {
                    "side": SIDE_LABELS[level.side],
                    "price": level.price / PRICE_SCALE,
                    "quantity": level.quantity,
                    "order_count": level.order_count,
                }
                for level in msg.levels
            ],
        }
    if isinstance(msg, Heartbeat):
        return None
    raise TypeError(f"unknown feed object type: {type(msg).__name__}")


def translate_exec_report(frame: DecodedFrame) -> dict[str, Any] | None:
    """Translate an execution-report frame (decoded from the TCP order
    gateway) to a dict. Heartbeats are dropped (same reason as feed).
    """
    if frame.type == MessageType.HEARTBEAT:
        return None
    if frame.report_type is None:
        # Inbound-only types should never reach the bridge, but be defensive.
        raise ValueError(f"non-report frame: type=0x{frame.type:02x}")
    return {
        "type": "exec_report",
        "report_type": _REPORT_LABELS.get(frame.report_type, f"T{frame.report_type}"),
        "order_id": frame.order_id,
        "client_id": frame.client_id,
        "side": SIDE_LABELS.get(frame.side, "?"),
        "price": frame.price / PRICE_SCALE,
        "executed_price": frame.executed_price / PRICE_SCALE,
        "executed_quantity": frame.executed_quantity,
        "remaining_quantity": frame.remaining_quantity,
        "counterparty_order_id": frame.counterparty_order_id,
        "ts_ns": frame.timestamp_nanos,
    }


def order_from_json(payload: Mapping[str, Any]) -> dict[str, int]:
    """Parse a browser-sent order payload and return integer fields.

    Expected shape::

        {"type": "new_order", "order_id": 7, "side": "BUY",
         "order_type": "LIMIT", "price": 100.50, "quantity": 10}

    ``price`` is a decimal in currency units; we scale to int64
    fixed-point here (the only correct place — browsers shouldn't see
    the int64 representation and the exchange shouldn't see floats).
    """
    order_types = {"LIMIT": 0, "MARKET": 1, "IOC": 2, "FOK": 3, "ICEBERG": 4}
    side = SIDE_BY_LABEL[payload["side"]]
    otype = order_types[payload["order_type"]]
    return {
        "client_id": int(payload.get("client_id", 0)),
        "order_id": int(payload["order_id"]),
        "side": side,
        "order_type": otype,
        "price": int(round(float(payload.get("price", 0.0)) * PRICE_SCALE)),
        "quantity": int(payload["quantity"]),
        "display_size": int(payload.get("display_size", 0)),
        "timestamp_nanos": int(payload.get("timestamp_nanos", 0)),
    }
