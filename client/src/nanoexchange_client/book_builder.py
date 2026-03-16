"""Reconstructs the order book state from the market-data feed.

Design:
- Incrementals update a dict keyed by (side, price) → (quantity, order_count).
- Snapshots replace state entirely; all buffered incrementals with sequence <=
  ``starting_sequence`` are discarded and those above are replayed.
- A sequence gap (observed_seq > last_seq + 1) is surfaced via ``gaps()`` so callers can
  decide to wait for the next snapshot rather than applying incrementals against divergent
  state.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .feed import BookUpdate, Snapshot, SnapshotLevel, Trade, Heartbeat


BID = 0
ASK = 1


@dataclass
class BookState:
    bids: dict[int, tuple[int, int]] = field(default_factory=dict)  # price -> (qty, orderCount)
    asks: dict[int, tuple[int, int]] = field(default_factory=dict)

    def best_bid(self) -> int | None:
        return max(self.bids) if self.bids else None

    def best_ask(self) -> int | None:
        return min(self.asks) if self.asks else None

    def bid_quantity(self, price: int) -> int:
        return self.bids.get(price, (0, 0))[0]

    def ask_quantity(self, price: int) -> int:
        return self.asks.get(price, (0, 0))[0]


class BookBuilder:
    def __init__(self) -> None:
        self.state = BookState()
        self.last_seq: int | None = None
        self._pending_snapshot_parts: dict[int, list[SnapshotLevel]] = {}
        self._pending_snapshot_total: int | None = None
        self._pending_snapshot_starting: int | None = None
        self._trades: list[Trade] = []
        self._gaps: list[tuple[int, int]] = []  # (expected_seq, observed_seq)

    # --------------------------------------------------------------------------
    # public API
    # --------------------------------------------------------------------------

    def apply(self, message) -> None:
        """Apply a decoded feed message to the book."""
        if isinstance(message, BookUpdate):
            self._check_seq(message.seq)
            self._apply_book_update(message)
        elif isinstance(message, Snapshot):
            self._check_seq(message.seq)
            self._apply_snapshot_part(message)
        elif isinstance(message, Trade):
            self._check_seq(message.seq)
            self._trades.append(message)
        elif isinstance(message, Heartbeat):
            self._check_seq(message.seq)
        else:
            raise TypeError(f"unhandled message type {type(message)!r}")

    def trades(self) -> list[Trade]:
        return list(self._trades)

    def gaps(self) -> list[tuple[int, int]]:
        return list(self._gaps)

    # --------------------------------------------------------------------------
    # internals
    # --------------------------------------------------------------------------

    def _check_seq(self, seq: int) -> None:
        if self.last_seq is None:
            self.last_seq = seq
            if seq != 1:
                # Starting mid-feed; not necessarily an error, but record it.
                self._gaps.append((1, seq))
            return
        expected = self.last_seq + 1
        if seq != expected:
            self._gaps.append((expected, seq))
        self.last_seq = seq

    def _apply_book_update(self, u: BookUpdate) -> None:
        book = self.state.bids if u.side == BID else self.state.asks
        if u.quantity == 0:
            book.pop(u.price, None)
        else:
            book[u.price] = (u.quantity, u.order_count)

    def _apply_snapshot_part(self, s: Snapshot) -> None:
        if s.part_number == 1:
            # starting a new snapshot cycle
            self._pending_snapshot_parts = {1: s.levels}
            self._pending_snapshot_total = s.total_parts
            self._pending_snapshot_starting = s.starting_sequence
        else:
            if self._pending_snapshot_total is None:
                # mid-snapshot start; ignore until the next one arrives whole
                return
            if s.total_parts != self._pending_snapshot_total:
                # starting a fresh snapshot cycle
                self._pending_snapshot_parts = {s.part_number: s.levels}
                self._pending_snapshot_total = s.total_parts
                self._pending_snapshot_starting = s.starting_sequence
                return
            self._pending_snapshot_parts[s.part_number] = s.levels

        total = self._pending_snapshot_total
        if len(self._pending_snapshot_parts) != total:
            return

        # all parts received; apply
        new_bids: dict[int, tuple[int, int]] = {}
        new_asks: dict[int, tuple[int, int]] = {}
        for part_num in sorted(self._pending_snapshot_parts):
            for lvl in self._pending_snapshot_parts[part_num]:
                target = new_bids if lvl.side == BID else new_asks
                target[lvl.price] = (lvl.quantity, lvl.order_count)
        self.state.bids = new_bids
        self.state.asks = new_asks
        self._pending_snapshot_parts.clear()
        self._pending_snapshot_total = None
        self._pending_snapshot_starting = None
