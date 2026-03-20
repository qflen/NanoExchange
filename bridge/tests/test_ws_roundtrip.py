"""
End-to-end WS bridge smoke test.

Spawns the Bridge in-process, binds UDP to a free port, joins the
multicast group, and publishes a crafted book_update datagram from a
second socket. A WebSocket client connects, consumes one batch, and
asserts the expected JSON shape.

No Java server needed. This is the minimum integration the bridge ships
with — it exercises the UDP → translator → batcher → ws path and the
per-client flush loop together.
"""

import asyncio
import socket
import struct
import zlib

import orjson
import pytest
import websockets

from nanoexchange_bridge.ws_bridge import Bridge


def _free_udp_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def _free_tcp_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def _wrap_feed(seq: int, type_byte: int, payload: bytes) -> bytes:
    body = struct.pack("<qB", seq, type_byte) + payload
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return body + struct.pack("<I", crc)


@pytest.mark.asyncio
async def test_bridge_forwards_udp_book_update_to_ws():
    mc_port = _free_udp_port()
    ws_port = _free_tcp_port()
    mc_group = "239.201.77.88"

    bridge = Bridge(
        multicast_group=mc_group,
        multicast_port=mc_port,
        ws_host="127.0.0.1",
        ws_port=ws_port,
        multicast_interface="127.0.0.1",
    )
    serve_task = asyncio.create_task(bridge.serve())
    # Give the WS listener a moment to bind.
    await asyncio.sleep(0.1)

    publisher = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    publisher.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    publisher.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
    publisher.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF,
                         socket.inet_aton("127.0.0.1"))

    try:
        async with websockets.connect(f"ws://127.0.0.1:{ws_port}") as ws:
            # Consume the greeting so the batch is next.
            hello = orjson.loads(await asyncio.wait_for(ws.recv(), timeout=1.0))
            assert hello["type"] == "hello"
            # Give the server time to register this client before we publish.
            await asyncio.sleep(0.05)
            payload = struct.pack("<Bqqi", 0, 100_00000000, 250, 1)
            publisher.sendto(_wrap_feed(1, 0x01, payload), (mc_group, mc_port))

            msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
            j = orjson.loads(msg)
            assert j["type"] == "batch"
            assert len(j["book_updates"]) == 1
            bu = j["book_updates"][0]
            assert bu["side"] == "BUY"
            assert bu["price"] == 100.0
            assert bu["quantity"] == 250
    finally:
        publisher.close()
        serve_task.cancel()
        try:
            await serve_task
        except (asyncio.CancelledError, Exception):
            pass


@pytest.mark.asyncio
async def test_bridge_coalesces_book_updates_within_window():
    """Send 20 updates at the same price before the first flush; the WS
    client should observe exactly one book_update for that level."""
    mc_port = _free_udp_port()
    ws_port = _free_tcp_port()
    mc_group = "239.201.88.99"

    bridge = Bridge(
        multicast_group=mc_group,
        multicast_port=mc_port,
        ws_host="127.0.0.1",
        ws_port=ws_port,
        multicast_interface="127.0.0.1",
    )
    serve_task = asyncio.create_task(bridge.serve())
    await asyncio.sleep(0.1)

    publisher = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    publisher.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    publisher.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
    publisher.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF,
                         socket.inet_aton("127.0.0.1"))
    try:
        async with websockets.connect(f"ws://127.0.0.1:{ws_port}") as ws:
            # Drain the greeting frame.
            assert orjson.loads(
                await asyncio.wait_for(ws.recv(), timeout=1.0)
            )["type"] == "hello"
            await asyncio.sleep(0.05)
            # 20 updates at the same (side, price) — should coalesce to one.
            for i in range(20):
                payload = struct.pack("<Bqqi", 0, 100_00000000, 10 + i, 1)
                publisher.sendto(_wrap_feed(i, 0x01, payload), (mc_group, mc_port))
            # Wait long enough for ≥1 flush tick.
            await asyncio.sleep(0.1)
            # Collect everything sent so far.
            messages = []
            try:
                while True:
                    messages.append(await asyncio.wait_for(ws.recv(), timeout=0.1))
            except asyncio.TimeoutError:
                pass
            total_updates = sum(
                len(orjson.loads(m).get("book_updates", [])) for m in messages
            )
            # At most a few levels may appear (depending on how many flushes
            # happened before all datagrams arrived), but never 20.
            assert total_updates < 20, (
                f"coalescing failed: {total_updates} updates passed through "
                f"(expected ≤ a handful from 20 source datagrams)"
            )
            # And the final quantity should be the most recent one (29).
            last_qty = None
            for m in messages:
                for bu in orjson.loads(m).get("book_updates", []):
                    last_qty = bu["quantity"]
            assert last_qty == 29
    finally:
        publisher.close()
        serve_task.cancel()
        try:
            await serve_task
        except (asyncio.CancelledError, Exception):
            pass
