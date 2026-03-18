"""
Latency histogram: takes a list of (send_ts_ns, recv_ts_ns) pairs and
produces a log-scale Plotly HTML figure plus the p50/p99/p99.9/max
summary statistics.

The source of the timestamp pairs is deliberately left to the caller:
the simulator captures them at the TCP boundary; a dedicated journal
reader could derive them from two paired records (NEW_ORDER and its
first ACK). Either way, the histogram shape is the artifact.

Log-scale on x because tail latency is where the interesting story
lives — a linear axis would crush it into one bar.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import numpy as np


@dataclass(slots=True)
class LatencyHistogram:
    samples_ns: np.ndarray
    p50_ns: float
    p99_ns: float
    p999_ns: float
    max_ns: float
    count: int


def summarise_latencies(pairs: Sequence[tuple[int, int]]) -> LatencyHistogram:
    if not pairs:
        empty = np.zeros(0)
        return LatencyHistogram(empty, 0.0, 0.0, 0.0, 0.0, 0)
    deltas = np.asarray([r - s for s, r in pairs], dtype=np.int64)
    deltas = deltas[deltas > 0]
    if deltas.size == 0:
        return LatencyHistogram(deltas, 0.0, 0.0, 0.0, 0.0, 0)
    return LatencyHistogram(
        samples_ns=deltas,
        p50_ns=float(np.percentile(deltas, 50)),
        p99_ns=float(np.percentile(deltas, 99)),
        p999_ns=float(np.percentile(deltas, 99.9)),
        max_ns=float(deltas.max()),
        count=int(deltas.size),
    )


def render_latency_histogram(
    hist: LatencyHistogram,
    output: str | Path,
    *,
    title: str = "Order ACK latency",
) -> Path:
    """Render ``hist`` to an interactive HTML file. Importing plotly is
    deferred so unit tests that only use the stats do not require it
    installed.
    """
    import plotly.graph_objects as go  # noqa: PLC0415 — deferred import by design

    out = Path(output)
    fig = go.Figure()
    if hist.count > 0:
        fig.add_trace(go.Histogram(
            x=hist.samples_ns,
            nbinsx=60,
            name="samples",
            marker=dict(color="#2563eb"),
        ))
        for label, value, colour in [
            ("p50", hist.p50_ns, "#16a34a"),
            ("p99", hist.p99_ns, "#f59e0b"),
            ("p99.9", hist.p999_ns, "#dc2626"),
        ]:
            fig.add_vline(
                x=value,
                line_color=colour,
                line_dash="dash",
                annotation_text=f"{label} = {value:,.0f} ns",
                annotation_position="top",
            )
    fig.update_layout(
        title=f"{title} — n={hist.count:,}",
        xaxis_title="Latency (ns, log scale)",
        yaxis_title="Count",
        xaxis_type="log",
        template="plotly_white",
        bargap=0.05,
    )
    fig.write_html(out, include_plotlyjs="cdn")
    return out
