"""
Simulator tests — pure logic, no network.

``run_in_memory`` replaces the engine with an instantaneous model, so
these complete in a few ms and exercise the market-maker state machine
deterministically.
"""

from nanoexchange_analytics.simulator import (
    InventoryMarketMaker,
    MarketMakerState,
    SimulatorConfig,
    run_in_memory,
)


def test_market_maker_inventory_skew_reduces_risk():
    """When inventory builds up on one side, the MM's quotes must shift
    to make further accumulation less likely. A long MM should quote a
    lower ask (easier to unload) and a lower bid (harder to buy more).
    """
    mm = InventoryMarketMaker(half_spread=0.10, inventory_skew=0.01)
    bid0, ask0 = mm.quotes_for(100.0)

    # Simulate the MM buying 50 lots.
    mm.state = MarketMakerState(inventory=50, cash=-5000.0)
    bid1, ask1 = mm.quotes_for(100.0)

    assert bid1 < bid0, f"long MM should quote lower bid (was {bid0}, now {bid1})"
    assert ask1 < ask0, f"long MM should quote lower ask (was {ask0}, now {ask1})"


def test_market_maker_fill_accounting():
    mm = InventoryMarketMaker(half_spread=0.05, inventory_skew=0.0)
    mm.observe_fill(side="buy", quantity=5, price=99.95)
    assert mm.state.inventory == 5
    assert mm.state.cash == -5 * 99.95
    mm.observe_fill(side="sell", quantity=3, price=100.05)
    assert mm.state.inventory == 2
    # Cash = -5 * 99.95 + 3 * 100.05
    assert abs(mm.state.cash - (-5 * 99.95 + 3 * 100.05)) < 1e-9


def test_market_maker_mark_to_market_uses_current_mid():
    """Mark-to-market must use current mid, not last trade. A taker hit
    at 100.05 and then a mid move to 100.00 should yield PnL = -5 bps
    times inventory, not zero."""
    mm = InventoryMarketMaker(half_spread=0.05, inventory_skew=0.0)
    mm.observe_fill(side="buy", quantity=10, price=100.05)
    # mid dropped by 0.05. Mark-to-market should be 10 * 100.00 - 10 * 100.05
    assert abs(mm.mark_to_market(100.00) - (-0.5)) < 1e-9


def test_run_in_memory_is_deterministic():
    cfg = SimulatorConfig(duration_s=0.5, lambda_per_sec=20.0, seed=1234)
    a = run_in_memory(cfg, tick_s=0.01)
    b = run_in_memory(cfg, tick_s=0.01)
    assert a.pnl_series == b.pnl_series
    assert a.fills == b.fills


def test_run_in_memory_has_nonzero_activity():
    """High arrival rate over 1s with cheap quotes → at least some fills.
    Also verifies the MM stays within sane inventory bounds (the inventory
    skew is the reason)."""
    cfg = SimulatorConfig(duration_s=1.0, lambda_per_sec=200.0,
                          inventory_skew=0.02, seed=99)
    result = run_in_memory(cfg, tick_s=0.01)
    assert result.fills > 10
    inventories = [v for _, v in result.inventory_series]
    # With a non-zero skew, inventory shouldn't drift monotonically forever.
    assert max(inventories) - min(inventories) < cfg.taker_size * result.fills
