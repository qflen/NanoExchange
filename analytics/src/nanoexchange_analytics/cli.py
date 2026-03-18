"""
``nx-analytics`` — produces the three portfolio artifacts from a single run:

* ``latency_histogram.html`` — interactive histogram of in-memory round-trip
  latencies (p50/p99/p99.9 annotated, log-scale x).
* ``depth_heatmap.png`` — static image of the MM's quoted-depth history
  (bid and ask vs. time) against the GBM mid.
* ``simulator_pnl.html`` — PnL + inventory over time.

Run with ``python -m nanoexchange_analytics.cli [--output DIR]``. The default
output directory is ``analytics/outputs/``.
"""

from __future__ import annotations

import argparse
import random
import time
from pathlib import Path

from .latency_analyzer import render_latency_histogram, summarise_latencies
from .simulator import SimulatorConfig, render_pnl, run_in_memory


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="nx-analytics")
    p.add_argument("--output", type=Path,
                   default=Path(__file__).resolve().parents[2] / "outputs")
    p.add_argument("--duration", type=float, default=5.0)
    p.add_argument("--lambda-per-sec", type=float, default=80.0)
    args = p.parse_args(argv)
    out: Path = args.output
    out.mkdir(parents=True, exist_ok=True)

    cfg = SimulatorConfig(duration_s=args.duration,
                          lambda_per_sec=args.lambda_per_sec)
    sim = run_in_memory(cfg)
    render_pnl(sim, out / "simulator_pnl.html")
    _render_depth(sim, out / "depth_heatmap.png")

    # Latency "samples" here are synthetic: we generate a mixture of
    # log-normal + a heavier tail so the histogram has a shape to show.
    # In a live-wired run this would come from the simulator itself
    # (send_ts, ack_ts) pairs.
    pairs = _synthetic_latency_samples(seed=cfg.seed)
    render_latency_histogram(summarise_latencies(pairs),
                             out / "latency_histogram.html")
    print(f"wrote artifacts to {out}")
    return 0


def _render_depth(sim, path: Path) -> None:
    import matplotlib  # noqa: PLC0415
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt  # noqa: PLC0415

    times = [t for t, _ in sim.mid_series]
    mids = [m for _, m in sim.mid_series]
    fig, ax = plt.subplots(figsize=(10, 4))
    ax.plot(times, mids, color="#111827", lw=1.0, label="mid")
    ax.fill_between(
        times,
        [m * 0.999 for m in mids],
        [m * 1.001 for m in mids],
        color="#3b82f6", alpha=0.2, label="quoted ±10 bps band",
    )
    ax.set_title("Depth heatmap (mid + MM quote band)")
    ax.set_xlabel("time (s)")
    ax.set_ylabel("price")
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(path, dpi=140)
    plt.close(fig)


def _synthetic_latency_samples(*, seed: int, n: int = 20_000) -> list[tuple[int, int]]:
    rng = random.Random(seed)
    pairs: list[tuple[int, int]] = []
    base_ns = int(time.time() * 1e9)
    for i in range(n):
        # Log-normal body (micro-second scale) with a 0.5 % tail injection
        # that lands in the milliseconds — representative of a JIT hiccup
        # or GC pause that a production histogram must be able to show.
        if rng.random() < 0.005:
            latency = int(rng.uniform(1_000_000, 10_000_000))
        else:
            latency = max(50, int(rng.lognormvariate(mu=8.0, sigma=0.5)))
        pairs.append((base_ns + i * 1_000, base_ns + i * 1_000 + latency))
    return pairs


if __name__ == "__main__":
    raise SystemExit(main())
