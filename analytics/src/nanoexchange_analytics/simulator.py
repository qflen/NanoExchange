"""
A small market-making simulator.

Three moving parts:

1. **Arrivals.** Taker events arrive as a Poisson process with intensity
   ``lambda_per_sec``. Direction (buy/sell) is 50/50 by default. The
   *only* reason arrivals are Poisson is that it's the standard null
   model — real flow is bursty and autocorrelated, but that's a
   calibration exercise, not a simulator change.

2. **Fair value.** A GBM path drives the "true" mid. The simulator uses
   this both to quote around and to mark-to-market the MM's inventory.

3. **Market maker.** An inventory-aware MM quotes a bid and an ask at
   ``mid ± (half_spread + inventory_skew · inventory)``. When long,
   it skews both quotes down (cheaper to sell, less attractive to buy);
   when short, skews up. The skew parameter has units of
   *price / unit-of-inventory*.

The simulator as a class covers only the business logic. Wiring it up to
the live engine happens in :func:`run_simulation`, which is
asyncio-based and uses the existing :mod:`nanoexchange_client.order_client`
to submit orders. Tests exercise the class directly with a fake engine so
they stay fast and deterministic.

The PnL accounting is mark-to-market against the *current mid*, not the
last traded price — trading against the last trade is a classic
simulation bug that produces misleading PnL curves (the MM appears to
earn its own spread on every fill).
"""

from __future__ import annotations

import asyncio
import math
import random
import time
from dataclasses import dataclass, field
from typing import Callable, Protocol

from .gbm import GbmPath


PRICE_SCALE = 100_000_000   # int64 fixed point, 8 decimals (matches PROTOCOL.md)


@dataclass(slots=True)
class SimulatorConfig:
    duration_s: float = 5.0
    lambda_per_sec: float = 50.0      # Poisson intensity of aggressive orders
    gbm_mu: float = 0.0
    gbm_sigma: float = 0.5            # annualised volatility
    gbm_dt: float = 1.0 / 252.0 / 390.0  # one trading minute in years
    fair_value: float = 100.0         # starting mid in currency units
    half_spread: float = 0.02
    inventory_skew: float = 0.001     # price units per unit of inventory
    mm_size: int = 10                 # lots per MM quote
    taker_size: int = 3
    seed: int = 0xC0FFEE


@dataclass(slots=True)
class MarketMakerState:
    inventory: int = 0
    cash: float = 0.0
    last_bid: float = 0.0
    last_ask: float = 0.0


@dataclass(slots=True)
class InventoryMarketMaker:
    """Inventory-aware market maker. Quote method is deterministic given
    the mid and current state; ``observe_fill`` is the only mutator.
    """
    half_spread: float
    inventory_skew: float
    state: MarketMakerState = field(default_factory=MarketMakerState)

    def quotes_for(self, mid: float) -> tuple[float, float]:
        """Return ``(bid, ask)`` around ``mid``.

        When we are long, ``skew`` is positive, bids and asks both shift
        down — the bid becomes less attractive (we quote lower) and the
        ask becomes more attractive (we also quote lower). Opposite when
        short. This is the textbook Ho–Stoll / Avellaneda-Stoikov
        inventory correction, stripped of risk-aversion and horizon
        terms because those don't change the shape of the behaviour this
        simulator is showcasing.
        """
        skew = self.inventory_skew * self.state.inventory
        bid = mid - self.half_spread - skew
        ask = mid + self.half_spread - skew
        self.state.last_bid = bid
        self.state.last_ask = ask
        return bid, ask

    def observe_fill(self, *, side: str, quantity: int, price: float) -> None:
        """Side is from the MM's perspective. ``'buy'`` means the MM bought
        (its bid was lifted); ``'sell'`` means it sold (its ask was hit)."""
        if side == "buy":
            self.state.inventory += quantity
            self.state.cash -= price * quantity
        elif side == "sell":
            self.state.inventory -= quantity
            self.state.cash += price * quantity
        else:
            raise ValueError(f"unknown side: {side!r}")

    def mark_to_market(self, mid: float) -> float:
        return self.state.cash + self.state.inventory * mid


class FillSource(Protocol):
    """Protocol the simulator uses to notify the MM of fills. Implemented by
    the live-engine adapter and by the test harness."""

    def next_taker(self, now_s: float) -> tuple[str, int] | None: ...


@dataclass(slots=True)
class PoissonTaker:
    """Generate aggressive taker events as a Poisson process with side 50/50."""
    lambda_per_sec: float
    size: int
    rng: random.Random
    _next_arrival_s: float = 0.0

    def seed(self, now_s: float) -> None:
        self._next_arrival_s = now_s + self._interarrival()

    def next_taker(self, now_s: float) -> tuple[str, int] | None:
        if now_s < self._next_arrival_s:
            return None
        side = "buy" if self.rng.random() < 0.5 else "sell"
        self._next_arrival_s = now_s + self._interarrival()
        return side, self.size

    def _interarrival(self) -> float:
        # Exponential inter-arrival times — the defining property of a
        # Poisson process. Using rng.expovariate directly so the seed is
        # honoured.
        return self.rng.expovariate(self.lambda_per_sec)


@dataclass(slots=True)
class SimulationResult:
    pnl_series: list[tuple[float, float]]       # (wall_time_s, mtm_pnl)
    mid_series: list[tuple[float, float]]       # (wall_time_s, mid)
    inventory_series: list[tuple[float, int]]
    fills: int
    submitted_orders: int
    executed_quantity: int


def run_in_memory(
    cfg: SimulatorConfig,
    *,
    tick_s: float = 0.01,
    on_step: Callable[[float, float, int, float], None] | None = None,
) -> SimulationResult:
    """Run the simulator *without* the live engine, for tests and for the
    PnL chart generator. The "engine" is replaced by an instantaneous
    model: the MM's quote is always lifted at the quoted price when a
    taker arrives, and the PnL is computed against the current mid.

    This cuts out everything TCP-shaped from the inner loop so the run
    completes in milliseconds. Behaviour of the MM state machine is
    identical to the live-wired path.
    """
    rng = random.Random(cfg.seed)
    path = GbmPath(mu=cfg.gbm_mu, sigma=cfg.gbm_sigma, dt=cfg.gbm_dt,
                   s0=cfg.fair_value, rng=rng)
    mm = InventoryMarketMaker(half_spread=cfg.half_spread,
                              inventory_skew=cfg.inventory_skew)
    taker = PoissonTaker(lambda_per_sec=cfg.lambda_per_sec,
                         size=cfg.taker_size, rng=rng)
    taker.seed(0.0)

    result = SimulationResult(
        pnl_series=[], mid_series=[], inventory_series=[],
        fills=0, submitted_orders=0, executed_quantity=0)

    steps = max(1, int(math.ceil(cfg.duration_s / tick_s)))
    for step in range(steps):
        now_s = step * tick_s
        mid = path.step()
        bid, ask = mm.quotes_for(mid)
        result.submitted_orders += 2  # new bid + new ask every tick
        while True:
            arrival = taker.next_taker(now_s)
            if arrival is None:
                break
            side, qty = arrival
            # Taker buys → MM sells at its ask.
            # Taker sells → MM buys at its bid.
            if side == "buy":
                mm.observe_fill(side="sell", quantity=qty, price=ask)
            else:
                mm.observe_fill(side="buy", quantity=qty, price=bid)
            result.fills += 1
            result.executed_quantity += qty
        mtm = mm.mark_to_market(mid)
        result.mid_series.append((now_s, mid))
        result.pnl_series.append((now_s, mtm))
        result.inventory_series.append((now_s, mm.state.inventory))
        if on_step is not None:
            on_step(now_s, mid, mm.state.inventory, mtm)
    return result


async def run_simulation(
    cfg: SimulatorConfig,
    *,
    host: str,
    port: int,
    mm_client_id: int = 100,
    taker_client_id: int = 200,
) -> SimulationResult:
    """Drive the live engine with two logical clients — the MM refreshes
    its quotes on a tick; a second client injects Poisson taker flow —
    and collect the resulting fills into a :class:`SimulationResult`.

    ``nanoexchange_client`` is imported lazily to keep test execution
    from requiring the client package on the path when only the
    in-memory simulator is exercised.
    """
    from nanoexchange_client import OrderClient  # noqa: PLC0415
    from nanoexchange_client.protocol import OrderType, Side  # noqa: PLC0415

    rng = random.Random(cfg.seed)
    path = GbmPath(mu=cfg.gbm_mu, sigma=cfg.gbm_sigma, dt=cfg.gbm_dt,
                   s0=cfg.fair_value, rng=rng)
    mm = InventoryMarketMaker(half_spread=cfg.half_spread,
                              inventory_skew=cfg.inventory_skew)
    taker = PoissonTaker(lambda_per_sec=cfg.lambda_per_sec,
                         size=cfg.taker_size, rng=rng)

    started = time.monotonic()
    taker.seed(0.0)
    result = SimulationResult([], [], [], 0, 0, 0)

    mm_client = OrderClient(host, port, client_id=mm_client_id)
    taker_client = OrderClient(host, port, client_id=taker_client_id)
    await mm_client.connect()
    await taker_client.connect()

    try:
        next_order_id = 1
        while True:
            now_s = time.monotonic() - started
            if now_s >= cfg.duration_s:
                break
            mid = path.step()
            bid, ask = mm.quotes_for(mid)
            await mm_client.submit_limit(
                order_id=next_order_id,
                side=Side.BUY,
                price=int(round(bid * PRICE_SCALE)),
                quantity=cfg.mm_size,
            )
            next_order_id += 1
            await mm_client.submit_limit(
                order_id=next_order_id,
                side=Side.SELL,
                price=int(round(ask * PRICE_SCALE)),
                quantity=cfg.mm_size,
            )
            next_order_id += 1
            result.submitted_orders += 2

            arrival = taker.next_taker(now_s)
            while arrival is not None:
                side, qty = arrival
                await taker_client.submit_new_order(
                    order_id=next_order_id,
                    side=Side.BUY if side == "buy" else Side.SELL,
                    order_type=OrderType.MARKET,
                    price=0,
                    quantity=qty,
                )
                next_order_id += 1
                arrival = taker.next_taker(now_s)

            await asyncio.sleep(0.01)
            result.mid_series.append((now_s, mid))
            result.pnl_series.append((now_s, mm.mark_to_market(mid)))
            result.inventory_series.append((now_s, mm.state.inventory))
    finally:
        await mm_client.close()
        await taker_client.close()

    return result


def render_pnl(result: SimulationResult, output) -> None:
    """Write an HTML PnL + inventory chart. Used by the analytics CLI."""
    import plotly.graph_objects as go  # noqa: PLC0415
    from plotly.subplots import make_subplots  # noqa: PLC0415

    fig = make_subplots(
        rows=2, cols=1, shared_xaxes=True,
        row_heights=[0.6, 0.4],
        subplot_titles=("Market-maker PnL (mark-to-market)", "Inventory"),
    )
    fig.add_trace(go.Scatter(
        x=[t for t, _ in result.pnl_series],
        y=[v for _, v in result.pnl_series],
        mode="lines", name="PnL", line=dict(color="#2563eb"),
    ), row=1, col=1)
    fig.add_trace(go.Scatter(
        x=[t for t, _ in result.inventory_series],
        y=[v for _, v in result.inventory_series],
        mode="lines", name="Inventory", line=dict(color="#dc2626"),
    ), row=2, col=1)
    fig.update_layout(template="plotly_white", showlegend=False,
                      title=f"Simulator run — fills={result.fills}, "
                            f"submitted={result.submitted_orders}")
    fig.update_xaxes(title_text="time (s)", row=2, col=1)
    fig.update_yaxes(title_text="PnL", row=1, col=1)
    fig.update_yaxes(title_text="lots", row=2, col=1)
    fig.write_html(output, include_plotlyjs="cdn")
