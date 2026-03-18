"""Latency-analyzer stats are straightforward to test on synthetic data.
The HTML rendering is exercised via a smoke test (``tmp_path`` file
exists and is non-empty). The chart's visual correctness is not
asserted — rendering that well requires visual inspection anyway."""

import numpy as np

from nanoexchange_analytics.latency_analyzer import (
    render_latency_histogram,
    summarise_latencies,
)


def test_empty_samples_produce_zero_summary():
    hist = summarise_latencies([])
    assert hist.count == 0
    assert hist.p50_ns == 0.0


def test_percentiles_agree_with_numpy():
    rng = np.random.default_rng(0)
    deltas = rng.integers(100, 10_000, size=5_000).astype(np.int64)
    pairs = [(0, int(d)) for d in deltas]
    hist = summarise_latencies(pairs)
    assert hist.count == len(deltas)
    assert abs(hist.p50_ns - float(np.percentile(deltas, 50))) < 1e-6
    assert abs(hist.p99_ns - float(np.percentile(deltas, 99))) < 1e-6


def test_negative_and_zero_deltas_are_dropped():
    pairs = [(0, 100), (10, 10), (20, 10), (0, 200)]
    hist = summarise_latencies(pairs)
    # Only positive deltas — 100 and 200 — are kept.
    assert hist.count == 2


def test_html_output_is_written(tmp_path):
    pairs = [(0, 50 + i) for i in range(200)]
    out = render_latency_histogram(summarise_latencies(pairs),
                                   tmp_path / "hist.html")
    assert out.exists()
    assert out.stat().st_size > 1024  # plotly HTML is always at least a few KB
