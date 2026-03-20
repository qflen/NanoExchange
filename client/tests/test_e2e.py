"""End-to-end test against a real ExchangeServer subprocess.

Skipped automatically if the Java server binary isn't on disk — run
``./gradlew :network:installDist`` from the repo root first. On CI this is the stage-13
regression gate; locally it's a smoke check.
"""

from __future__ import annotations

import asyncio
import os
import pathlib
import socket
import subprocess
import time

import pytest

from nanoexchange_client import (
    BookBuilder, FeedHandler, OrderClient, Side,
    ReportType, MessageType,
)


REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SERVER_BIN = REPO_ROOT / "network" / "build" / "install" / "network" / "bin" / "network"


def _free_tcp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _free_udp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def server():
    if not SERVER_BIN.exists():
        pytest.skip(f"server binary missing at {SERVER_BIN}; run ./gradlew :network:installDist")

    tcp_port = _free_tcp_port()
    mc_port = _free_udp_port()
    mc_group = "239.200.6.42"

    env = os.environ.copy()
    # The server is built with Java 21. If the ambient JAVA_HOME is older, prefer an
    # explicit Homebrew install; either way we don't rely on a system default.
    if "NX_JAVA_HOME" in env:
        env["JAVA_HOME"] = env["NX_JAVA_HOME"]
    elif os.path.isdir("/opt/homebrew/opt/openjdk@21"):
        env["JAVA_HOME"] = "/opt/homebrew/opt/openjdk@21"
    proc = subprocess.Popen(
        [str(SERVER_BIN), str(tcp_port), mc_group, str(mc_port)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, text=True,
    )

    try:
        # Wait for READY banner; give the JVM a generous window on cold start.
        deadline = time.time() + 20
        while time.time() < deadline:
            line = proc.stdout.readline()
            if not line:
                if proc.poll() is not None:
                    err = proc.stderr.read()
                    raise RuntimeError(f"server died before READY: {err}")
                continue
            if line.startswith("READY"):
                break
        else:
            raise RuntimeError("server never emitted READY")

        yield tcp_port, mc_group, mc_port
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


async def _run_scenario(tcp_port: int, mc_group: str, mc_port: int):
    feed = FeedHandler(mc_group, mc_port, iface="127.0.0.1")
    await feed.start()

    buyer = OrderClient("127.0.0.1", tcp_port, client_id=1001)
    await buyer.connect()
    seller = OrderClient("127.0.0.1", tcp_port, client_id=1002)
    await seller.connect()

    try:
        # Resting bid at 100, then crossing ask at 100 for half the quantity.
        await buyer.submit_limit(order_id=1, side=Side.BUY, price=100_00000000, quantity=10)
        buyer_reports = await buyer.read_reports(1, timeout=5.0)
        assert buyer_reports[0].type == MessageType.ACK
        assert buyer_reports[0].report_type == ReportType.ACK

        await seller.submit_limit(order_id=2, side=Side.SELL, price=100_00000000, quantity=4)
        # Engine emits ACK then FILL to the aggressor.
        seller_reports = await seller.read_reports(2, timeout=5.0)
        types = [r.report_type for r in seller_reports]
        assert types[0] == ReportType.ACK
        assert types[1] in (ReportType.FILL, ReportType.PARTIAL_FILL)
        assert seller_reports[1].executed_quantity == 4

        # The maker (buyer) gets a PARTIAL_FILL for 4 — 6 remains resting.
        buyer_more = await buyer.read_reports(1, timeout=5.0)
        assert buyer_more[0].report_type in (ReportType.FILL, ReportType.PARTIAL_FILL)
        assert buyer_more[0].executed_quantity == 4
        assert buyer_more[0].remaining_quantity == 6

        # Drain feed queue briefly and let BookBuilder reconstruct.
        bb = BookBuilder()
        end = time.time() + 1.0
        while time.time() < end:
            try:
                msg = await asyncio.wait_for(feed.queue.get(), timeout=0.2)
            except asyncio.TimeoutError:
                continue
            bb.apply(msg)

        # At least one trade should have hit the feed.
        trades = bb.trades()
        assert trades, "no trades observed on market-data feed"
        assert trades[0].price == 100_00000000
        assert trades[0].quantity == 4

        # Resting buy of 6 should still be visible via an emitted BOOK_UPDATE.
        assert bb.state.bid_quantity(100_00000000) == 6 or bb.state.best_bid() == 100_00000000

    finally:
        await buyer.close()
        await seller.close()
        await feed.close()


def test_end_to_end_match_against_real_server(server):
    tcp_port, mc_group, mc_port = server
    asyncio.run(_run_scenario(tcp_port, mc_group, mc_port))
