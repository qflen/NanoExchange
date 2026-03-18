"""
Asyncio load generator: sweeps a target offered-load curve and measures
the latency response at each point.

Each "point" is a short run at a fixed sustained-send rate. We submit
LIMIT orders that don't cross (alternating bids well below and asks well
above the opening mid) so the engine exercises its resting-insert path
without triggering a match avalanche. The metric is ACK round-trip
latency, measured as (now - send_ts) on the first execution report for
each order.

Usage::

    python bench/load_test.py --host 127.0.0.1 --port 9000 \\
        --rates 1000,5000,10000,20000 --duration 3

The output is a JSON file with one row per rate point, summarising
p50/p99/p99.9/max latency and achieved-vs-target throughput. A companion
Plotly chart is written next to it.

The generator itself must not be the bottleneck — profile it with
``python -X dev -m cProfile`` if in doubt, and prefer ``uvloop`` where
available (imported opportunistically below).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

# Allow the load test to be run from the repo root without installing the
# packages first: add the client source tree to sys.path.
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "client" / "src"))

from nanoexchange_client import OrderClient  # noqa: E402
from nanoexchange_client.protocol import Side  # noqa: E402

try:
    import uvloop  # type: ignore[import-not-found]
    uvloop.install()
except ImportError:  # pragma: no cover — optional
    pass


async def run_point(
    host: str, port: int, *, rate_per_sec: float, duration_s: float,
) -> dict:
    """Generate ``rate_per_sec`` orders for ``duration_s`` seconds, return
    a summary dict. Uses a single client — multiple clients would only
    matter once the gateway becomes the bottleneck."""
    client = OrderClient(host, port, client_id=7777)
    await client.connect()
    # Drain any startup frames
    await asyncio.sleep(0.05)

    send_times: dict[int, float] = {}
    latencies_ns: list[int] = []
    done = asyncio.Event()

    async def consume():
        async for frame in client.reports():
            now = time.perf_counter_ns()
            oid = getattr(frame, "order_id", None)
            if oid in send_times:
                sent_ns = int(send_times.pop(oid) * 1e9)
                latencies_ns.append(now - sent_ns)
            if done.is_set() and not send_times:
                return

    consumer = asyncio.create_task(consume())
    try:
        interval = 1.0 / rate_per_sec
        start = time.perf_counter()
        next_id = int(time.time_ns() % 1_000_000) * 100
        deadline = start + duration_s
        sent = 0
        while True:
            now = time.perf_counter()
            if now >= deadline:
                break
            next_id += 1
            send_times[next_id] = now
            # Alternate non-crossing bids and asks; price range is far
            # from any likely mid so the load test is a pure insert
            # stress — no matches, no trade publishing.
            side = Side.BUY if (next_id & 1) else Side.SELL
            price = 1_00000000 if side == Side.BUY else 10_000_00000000
            await client.submit_limit(
                order_id=next_id,
                side=side,
                price=price,
                quantity=1,
            )
            sent += 1
            # Pace: sleep until the next slot. asyncio.sleep(0) yields;
            # anything smaller than ~50µs is dominated by loop overhead.
            target = start + sent * interval
            remaining = target - time.perf_counter()
            if remaining > 0:
                await asyncio.sleep(remaining)

        done.set()
        # Give outstanding ACKs up to 500ms to arrive
        await asyncio.wait_for(consumer, timeout=0.5)
    except asyncio.TimeoutError:
        consumer.cancel()
    finally:
        await client.close()

    latencies_ns.sort()
    elapsed = max(1e-9, time.perf_counter() - start)
    def pct(p: float) -> float:
        if not latencies_ns:
            return 0.0
        idx = min(len(latencies_ns) - 1, int(p * len(latencies_ns)))
        return float(latencies_ns[idx])
    return {
        "rate_target": rate_per_sec,
        "rate_achieved": sent / elapsed,
        "orders_sent": sent,
        "orders_ack": len(latencies_ns),
        "p50_ns": pct(0.50),
        "p99_ns": pct(0.99),
        "p999_ns": pct(0.999),
        "max_ns": float(latencies_ns[-1]) if latencies_ns else 0.0,
    }


async def run_curve(
    host: str, port: int, *,
    rates: list[float], duration_s: float, out: Path,
) -> None:
    points: list[dict] = []
    for r in rates:
        print(f"load point: {r:,.0f} orders/s for {duration_s}s …", flush=True)
        point = await run_point(host, port, rate_per_sec=r,
                                duration_s=duration_s)
        points.append(point)
        print(json.dumps(point), flush=True)
    out.write_text(json.dumps({"points": points}, indent=2))
    _render_chart(points, out.with_suffix(".html"))


def _render_chart(points: list[dict], path: Path) -> None:
    try:
        import plotly.graph_objects as go  # noqa: PLC0415
    except ImportError:
        return
    fig = go.Figure()
    xs = [p["rate_achieved"] for p in points]
    fig.add_trace(go.Scatter(x=xs, y=[p["p50_ns"] for p in points],
                             mode="lines+markers", name="p50"))
    fig.add_trace(go.Scatter(x=xs, y=[p["p99_ns"] for p in points],
                             mode="lines+markers", name="p99"))
    fig.add_trace(go.Scatter(x=xs, y=[p["p999_ns"] for p in points],
                             mode="lines+markers", name="p99.9"))
    fig.update_layout(
        title="Order-ACK latency vs. offered load",
        xaxis_title="achieved rate (orders/sec)",
        yaxis_title="latency (ns, log scale)",
        yaxis_type="log",
        template="plotly_white",
    )
    fig.write_html(path, include_plotlyjs="cdn")


def _parse_rates(spec: str) -> list[float]:
    return [float(x) for x in spec.split(",") if x.strip()]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="load_test")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, required=True)
    p.add_argument("--rates", default="1000,5000,10000,20000",
                   help="comma-separated target orders/sec")
    p.add_argument("--duration", type=float, default=3.0)
    p.add_argument("--out", type=Path,
                   default=Path(__file__).resolve().parent / "load_results.json")
    args = p.parse_args(argv)
    asyncio.run(run_curve(
        args.host, args.port,
        rates=_parse_rates(args.rates),
        duration_s=args.duration,
        out=args.out,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
