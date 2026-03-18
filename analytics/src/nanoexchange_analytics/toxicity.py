"""
Flow-toxicity metrics.

The two we compute:

1. **VPIN** — Volume-Synchronised Probability of Informed Trading,
   Easley, López de Prado & O'Hara (2012). Trades are grouped into
   equal-volume *buckets* (not equal time). Within each bucket each
   trade is split into "buy-side" and "sell-side" volume using *Bulk
   Volume Classification*:

       buy_fraction(trade) = Φ( (p_trade − p_prev) / σ_ΔP )

   where Φ is the standard-normal CDF and σ_ΔP is the standard deviation
   of successive price changes observed in the data. Per-bucket toxicity
   is |V_B − V_S| / V, and VPIN is a rolling mean of that over the last
   *window* buckets.

2. **Order-to-trade ratio** — submitted-order count divided by traded-order
   count over a given window. Deliberately a cruder metric than VPIN, but
   cheap and surfaces quote stuffing well.

Both metrics are defined cleanly enough that a known-answer test can
hold them accountable: see ``tests/test_toxicity.py``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Sequence

import numpy as np
from scipy.stats import norm


@dataclass(frozen=True, slots=True)
class Trade:
    price: float
    quantity: float
    timestamp_ns: int


@dataclass(frozen=True, slots=True)
class ToxicityMetrics:
    vpin: float
    vpin_series: list[float]
    order_to_trade_ratio: float
    bucket_size: float
    n_buckets: int


def compute_vpin(
    trades: Sequence[Trade],
    *,
    bucket_size: float,
    window: int = 50,
) -> tuple[float, list[float]]:
    """Return ``(current_vpin, series)`` where ``series`` is the per-bucket
    toxicity values in time order.

    ``bucket_size`` is the *volume* per bucket (not a time window) — this is
    the defining feature of VPIN relative to older PIN variants. ``window``
    is the rolling-mean length, in buckets. If fewer than ``window`` buckets
    have completed, the current VPIN is the mean of all completed buckets.

    Returns ``(0.0, [])`` when fewer than one bucket has accumulated.
    """
    if bucket_size <= 0:
        raise ValueError("bucket_size must be positive")
    if len(trades) < 2:
        return 0.0, []

    prices = np.asarray([t.price for t in trades], dtype=float)
    qtys = np.asarray([t.quantity for t in trades], dtype=float)
    dp = np.diff(prices)
    sigma = float(np.std(dp, ddof=0))
    if sigma == 0.0:
        # Degenerate input — every trade at the same price. Classify as
        # half buy, half sell; all buckets have zero toxicity.
        buy_fracs = np.full_like(prices, 0.5)
    else:
        # BVC: first trade has no previous price, treat it as neutral.
        z = np.concatenate([[0.0], dp / sigma])
        buy_fracs = norm.cdf(z)

    buy_vol = buy_fracs * qtys
    sell_vol = (1.0 - buy_fracs) * qtys

    bucket_toxicity: list[float] = []
    bucket_buy = 0.0
    bucket_sell = 0.0
    bucket_total = 0.0
    for b, s, q in zip(buy_vol, sell_vol, qtys):
        remaining = q
        bb = b
        ss = s
        while remaining > 0:
            space = bucket_size - bucket_total
            if remaining < space:
                bucket_buy += bb
                bucket_sell += ss
                bucket_total += remaining
                break
            ratio = space / remaining
            bucket_buy += bb * ratio
            bucket_sell += ss * ratio
            bucket_total += space
            remaining -= space
            bb *= (1 - ratio)
            ss *= (1 - ratio)
            tox = abs(bucket_buy - bucket_sell) / bucket_size
            bucket_toxicity.append(tox)
            bucket_buy = 0.0
            bucket_sell = 0.0
            bucket_total = 0.0

    if not bucket_toxicity:
        return 0.0, []

    tail = bucket_toxicity[-window:]
    return float(np.mean(tail)), bucket_toxicity


def order_to_trade_ratio(
    submitted_orders: int,
    executed_trades: int,
) -> float:
    """Return submitted/executed. By convention returns ``inf`` when no trades
    occurred (every order was a cancel or a quote refresh) rather than dividing
    by zero, since that is the diagnostic signal: "lots of orders, no fills"."""
    if executed_trades <= 0:
        return float("inf")
    return submitted_orders / executed_trades


def summarise(
    trades: Iterable[Trade],
    submitted_orders: int,
    *,
    bucket_size: float,
    window: int = 50,
) -> ToxicityMetrics:
    tlist = list(trades)
    vpin, series = compute_vpin(tlist, bucket_size=bucket_size, window=window)
    ratio = order_to_trade_ratio(submitted_orders, len(tlist))
    return ToxicityMetrics(
        vpin=vpin,
        vpin_series=series,
        order_to_trade_ratio=ratio,
        bucket_size=bucket_size,
        n_buckets=len(series),
    )
