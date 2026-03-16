"""BookBuilder state-reconstruction tests."""

from __future__ import annotations

from nanoexchange_client.book_builder import BookBuilder, BID, ASK
from nanoexchange_client.feed import (
    BookUpdate, Heartbeat, Snapshot, SnapshotLevel, Trade,
)


def _bu(seq: int, side: int, price: int, qty: int, count: int = 1) -> BookUpdate:
    return BookUpdate(seq=seq, side=side, price=price, quantity=qty, order_count=count)


def test_apply_incrementals_builds_book():
    bb = BookBuilder()
    bb.apply(_bu(1, BID, 100, 10))
    bb.apply(_bu(2, BID, 99, 20))
    bb.apply(_bu(3, ASK, 101, 5))
    assert bb.state.best_bid() == 100
    assert bb.state.best_ask() == 101
    assert bb.state.bid_quantity(99) == 20


def test_zero_quantity_removes_level():
    bb = BookBuilder()
    bb.apply(_bu(1, BID, 100, 10))
    bb.apply(_bu(2, BID, 100, 0))
    assert bb.state.best_bid() is None


def test_gap_is_detected():
    bb = BookBuilder()
    bb.apply(_bu(1, BID, 100, 10))
    bb.apply(_bu(3, BID, 99, 5))  # missed seq 2
    gaps = bb.gaps()
    assert gaps == [(2, 3)]


def test_starting_mid_feed_is_recorded_as_gap():
    bb = BookBuilder()
    bb.apply(_bu(42, BID, 100, 10))
    assert bb.gaps() == [(1, 42)]


def test_snapshot_replaces_state():
    bb = BookBuilder()
    bb.apply(_bu(1, BID, 100, 10))
    bb.apply(_bu(2, ASK, 101, 10))
    snap = Snapshot(
        seq=3, starting_sequence=3, part_number=1, total_parts=1,
        levels=[
            SnapshotLevel(side=BID, price=90, quantity=1, order_count=1),
            SnapshotLevel(side=ASK, price=110, quantity=2, order_count=1),
        ],
    )
    bb.apply(snap)
    assert bb.state.best_bid() == 90
    assert bb.state.best_ask() == 110
    # old levels are gone
    assert bb.state.bid_quantity(100) == 0


def test_multi_part_snapshot_reassembles_atomically():
    bb = BookBuilder()
    part1 = Snapshot(
        seq=10, starting_sequence=10, part_number=1, total_parts=2,
        levels=[SnapshotLevel(side=BID, price=100, quantity=5, order_count=1)],
    )
    part2 = Snapshot(
        seq=11, starting_sequence=10, part_number=2, total_parts=2,
        levels=[SnapshotLevel(side=ASK, price=101, quantity=5, order_count=1)],
    )
    bb.apply(part1)
    # mid-reassembly, no levels applied yet
    assert bb.state.best_bid() is None
    bb.apply(part2)
    assert bb.state.best_bid() == 100
    assert bb.state.best_ask() == 101


def test_trade_is_recorded():
    bb = BookBuilder()
    bb.apply(Trade(seq=1, taker_order_id=1, maker_order_id=2, taker_side=0,
                   price=100, quantity=5, timestamp_nanos=123))
    assert len(bb.trades()) == 1
    assert bb.trades()[0].price == 100


def test_heartbeat_advances_sequence_without_gap():
    bb = BookBuilder()
    bb.apply(_bu(1, BID, 100, 10))
    bb.apply(Heartbeat(seq=2))
    bb.apply(_bu(3, BID, 99, 5))
    assert bb.gaps() == []
