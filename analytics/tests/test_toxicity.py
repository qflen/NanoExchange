"""
VPIN known-answer tests.

Rather than calibrate against live data, we exercise the math with
synthesized inputs where the correct answer can be derived by hand:

* A steady stream of trades at a single price yields bucket toxicity =
  0 (every bucket is perfectly balanced 50/50 under BVC).
* A strictly upward price path classifies every trade as ~100 % buy,
  so the per-bucket toxicity approaches 1.0.
* A perfectly alternating up/down tape classifies trades symmetrically,
  and buckets end up balanced (toxicity near 0).

The asserts use wide numerical tolerances because BVC uses the empirical
std-dev of price changes, which has finite-sample noise.
"""

import numpy as np

from nanoexchange_analytics.toxicity import (
    Trade,
    compute_vpin,
    order_to_trade_ratio,
)


def _steady_tape(n, price=100.0, qty=1.0):
    return [Trade(price=price, quantity=qty, timestamp_ns=i) for i in range(n)]


def test_vpin_uniform_price_is_zero():
    trades = _steady_tape(1000)
    vpin, series = compute_vpin(trades, bucket_size=50.0, window=10)
    assert len(series) > 0
    # BVC on constant prices returns buy_frac = 0.5 → toxicity = 0.
    assert vpin == 0.0


def test_vpin_upward_drift_is_high():
    trades = [Trade(price=100.0 + i * 0.01, quantity=1.0, timestamp_ns=i)
              for i in range(1000)]
    vpin, series = compute_vpin(trades, bucket_size=50.0, window=10)
    # Every price change is positive → every non-first trade classifies
    # as 100% buy. Tail VPIN lands near 1.
    assert vpin > 0.9
    assert all(s >= 0.0 for s in series)


def test_vpin_alternating_symmetry():
    # Zig-zag around a mean; should produce roughly balanced buckets.
    trades = []
    for i in range(2000):
        p = 100.0 + (0.05 if i % 2 == 0 else -0.05)
        trades.append(Trade(price=p, quantity=1.0, timestamp_ns=i))
    vpin, series = compute_vpin(trades, bucket_size=50.0, window=20)
    assert vpin < 0.05


def test_vpin_bucket_splitting_is_stable():
    """A single very large trade should split across multiple buckets
    without losing volume. Total volume across all completed buckets
    equals bucket_size × n_buckets (by definition of bucket completion)."""
    trades = [Trade(price=100.0, quantity=1000.0, timestamp_ns=0),
              Trade(price=100.0, quantity=1000.0, timestamp_ns=1)]
    _, series = compute_vpin(trades, bucket_size=50.0, window=1)
    assert len(series) == 40  # 2000 units / 50 per bucket


def test_order_to_trade_ratio_infinite_when_no_trades():
    assert order_to_trade_ratio(submitted_orders=500, executed_trades=0) == float("inf")


def test_order_to_trade_ratio_basic():
    assert order_to_trade_ratio(500, 100) == 5.0


def test_vpin_empty_input():
    vpin, series = compute_vpin([], bucket_size=10.0)
    assert vpin == 0.0 and series == []


def test_vpin_bucket_size_must_be_positive():
    import pytest
    with pytest.raises(ValueError):
        compute_vpin(_steady_tape(10), bucket_size=0)
