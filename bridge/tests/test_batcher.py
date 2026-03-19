"""Batcher coalescing rules, tested in isolation — no sockets involved."""

from nanoexchange_bridge.batcher import Batcher


def _bu(side: str, price: float, qty: int, *, seq: int = 0) -> dict:
    return {"type": "book_update", "seq": seq, "side": side,
            "price": price, "quantity": qty, "order_count": 1}


def test_book_updates_coalesce_by_side_and_price():
    b = Batcher()
    b.add(_bu("BUY", 100.0, 10, seq=1))
    b.add(_bu("BUY", 100.0, 20, seq=2))
    b.add(_bu("BUY", 100.0, 30, seq=3))
    b.add(_bu("BUY", 100.5, 5,  seq=4))
    window = b.flush()
    assert window is not None
    msgs = window.as_message()["book_updates"]
    assert len(msgs) == 2  # two distinct (side, price) keys
    by_price = {m["price"]: m for m in msgs}
    assert by_price[100.0]["quantity"] == 30  # last update wins
    assert by_price[100.0]["seq"] == 3
    assert by_price[100.5]["quantity"] == 5
    assert b.book_updates_in == 4
    assert b.book_updates_out == 2


def test_trades_are_never_coalesced():
    b = Batcher()
    for i in range(10):
        b.add({"type": "trade", "seq": i, "price": 100.0, "quantity": 1,
               "taker_order_id": i, "maker_order_id": 0,
               "taker_side": "BUY", "ts_ns": 0})
    window = b.flush()
    assert window is not None
    assert len(window.as_message()["trades"]) == 10


def test_exec_reports_are_never_coalesced():
    b = Batcher()
    b.add({"type": "exec_report", "report_type": "PARTIAL_FILL", "order_id": 1})
    b.add({"type": "exec_report", "report_type": "FILL",         "order_id": 1})
    window = b.flush()
    assert window is not None
    out = window.as_message()["execs"]
    assert [m["report_type"] for m in out] == ["PARTIAL_FILL", "FILL"]


def test_snapshot_supersedes_pending_book_updates():
    """A snapshot is an authoritative state — any pending deltas in the
    same window are stale (the UI will apply the snapshot first)."""
    b = Batcher()
    b.add(_bu("BUY", 100.0, 10))
    b.add({"type": "snapshot", "seq": 50, "levels": []})
    b.add(_bu("SELL", 101.0, 5))  # arrives after the snapshot, survives
    window = b.flush()
    assert window is not None
    out = window.as_message()
    assert len(out["snapshots"]) == 1
    assert len(out["book_updates"]) == 1
    assert out["book_updates"][0]["side"] == "SELL"


def test_flush_on_empty_window_returns_none():
    assert Batcher().flush() is None


def test_heartbeat_messages_are_dropped():
    b = Batcher()
    b.add({"type": "heartbeat"})   # drop
    b.add({"type": None})          # drop
    b.add(_bu("BUY", 100.0, 1))    # survives
    window = b.flush()
    assert window is not None
    assert len(window.as_message()["book_updates"]) == 1
    assert window.as_message()["execs"] == []
