"""
16 ms-window aggregator.

The UI's effective refresh rate is one tick per animation frame (~60 Hz).
There is no point delivering 500 book updates per second when only 60
render passes happen — the browser would spend all its time parsing
JSON it's about to throw away. The bridge therefore collects messages
over a 16 ms window and emits a single JSON batch per client at flush
time.

**Coalescing rules** (per slice 9 spec):

* Book updates are *keyed* by ``(side, price)`` — only the latest update
  per level is kept. Sequence numbers are preserved so the UI can detect
  gaps if it cares.
* Trades are *not* coalesced. Each is a distinct event the UI uses to
  draw the tape; collapsing them would destroy the tape.
* Execution reports are *not* coalesced. A partial-fill + a subsequent
  fill are two observations the order-status view needs to render
  sequentially.
* Snapshots *replace* any previously queued book updates in the same
  batch — a new snapshot makes earlier deltas redundant.

The batcher is synchronous. Flushing to a WebSocket is the caller's
responsibility; the batcher just returns the dict-of-lists. This keeps
it trivially testable.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class BatchWindow:
    """One window's worth of coalesced messages."""
    book_updates: dict[tuple[str, float], dict[str, Any]] = field(default_factory=dict)
    trades: list[dict[str, Any]] = field(default_factory=list)
    execs: list[dict[str, Any]] = field(default_factory=list)
    snapshots: list[dict[str, Any]] = field(default_factory=list)

    def is_empty(self) -> bool:
        return not (self.book_updates or self.trades or self.execs or self.snapshots)

    def as_message(self) -> dict[str, Any]:
        return {
            "type": "batch",
            "book_updates": list(self.book_updates.values()),
            "trades": self.trades,
            "execs": self.execs,
            "snapshots": self.snapshots,
        }


class Batcher:
    """Accumulate messages; flush on demand.

    The batcher never owns a timer — the caller schedules a periodic
    :meth:`flush` (typically every 16 ms). This keeps the object sync
    and testable, and lets the bridge use different cadences per
    deployment (a tick-heavy display could halve the window; a
    bandwidth-starved one could double it).
    """

    def __init__(self) -> None:
        self._window = BatchWindow()
        # Coalesce counters are public for tests and metrics.
        self.book_updates_in: int = 0
        self.book_updates_out: int = 0

    def add(self, message: dict[str, Any]) -> None:
        """Accept a JSON-shaped dict (as produced by ``translator``)."""
        t = message.get("type")
        if t == "book_update":
            key = (message["side"], message["price"])
            self._window.book_updates[key] = message
            self.book_updates_in += 1
        elif t == "trade":
            self._window.trades.append(message)
        elif t == "exec_report":
            self._window.execs.append(message)
        elif t == "snapshot":
            self._window.snapshots.append(message)
            # A fresh snapshot supersedes pending deltas — the UI will
            # apply the snapshot first.
            self._window.book_updates.clear()
        elif t in (None, "heartbeat"):
            # translator returns None for heartbeats; guard against it
            # anyway in case a caller does pass one through.
            return
        else:
            # Unknown types are accepted but tagged; let the UI decide.
            self._window.execs.append(message)

    def flush(self) -> BatchWindow | None:
        """Return the pending window and start a fresh one. Returns
        ``None`` if the window is empty — the caller should skip the
        send entirely rather than write an empty ``{"type":"batch"}``
        message."""
        if self._window.is_empty():
            return None
        self.book_updates_out += len(self._window.book_updates)
        w = self._window
        self._window = BatchWindow()
        return w
