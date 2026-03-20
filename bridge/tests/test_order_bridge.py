"""
End-to-end order-path test: the browser sends a new_order JSON over
WS, the bridge forwards it as a binary frame on a TCP connection, a
test stand-in acts as the engine, decodes the frame, and pushes back
a PARTIAL_FILL report. The bridge must route that report onto the
same WS session as an exec_report inside the next batch flush.
"""

import asyncio
import socket
import struct
import zlib

import orjson
import pytest
import websockets

from nanoexchange_bridge.ws_bridge import Bridge
from nanoexchange_client.protocol import MessageType


# Mirror of the Java-side wire format. The client library intentionally
# only decodes exec reports (inbound-to-client); the engine side lives
# in Java. For this test we hand-roll a minimal parser for NEW_ORDER
# and an encoder for PARTIAL_FILL so the fake engine can stand in.
_NEW_ORDER_PAYLOAD = struct.Struct("<qqBBqqqq")
_EXEC_REPORT_PAYLOAD = struct.Struct("<qqBqqqqqBq")


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


def _wrap_frame(type_byte: int, payload: bytes) -> bytes:
    body = bytes([type_byte]) + payload
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return struct.pack("<i", len(body) + 4) + body + struct.pack("<I", crc)


def _encode_partial_fill(
    *, order_id: int, client_id: int, side: int, price: int,
    executed_price: int, executed_quantity: int, remaining_quantity: int,
    counterparty_order_id: int, timestamp_nanos: int = 0,
) -> bytes:
    payload = _EXEC_REPORT_PAYLOAD.pack(
        order_id, client_id, side, price,
        executed_quantity, executed_price, remaining_quantity,
        counterparty_order_id, 0, timestamp_nanos,
    )
    return _wrap_frame(MessageType.PARTIAL_FILL, payload)


class _FakeEngine:
    """Minimal TCP stand-in for the order gateway. Decodes NEW_ORDER
    frames and pushes a PARTIAL_FILL back for each."""

    def __init__(self) -> None:
        self.received_new_orders: list[dict] = []
        self.connected = asyncio.Event()

    async def start(self) -> int:
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        return self._server.sockets[0].getsockname()[1]

    async def close(self) -> None:
        self._server.close()
        await self._server.wait_closed()

    async def _handle(self, reader: asyncio.StreamReader,
                      writer: asyncio.StreamWriter) -> None:
        self.connected.set()
        buf = bytearray()
        try:
            while True:
                chunk = await reader.read(4096)
                if not chunk:
                    return
                buf.extend(chunk)
                while len(buf) >= 4:
                    (length,) = struct.unpack_from("<i", buf, 0)
                    total = 4 + length
                    if len(buf) < total:
                        break
                    body = bytes(buf[4 : 4 + length - 4])
                    expected = int.from_bytes(buf[4 + length - 4 : total], "little", signed=False)
                    actual = zlib.crc32(body) & 0xFFFFFFFF
                    assert expected == actual, "bad CRC from bridge"
                    type_byte = body[0]
                    payload = body[1:]
                    del buf[:total]
                    if type_byte == MessageType.NEW_ORDER:
                        (client_id, order_id, side, otype, price,
                         qty, _disp, _ts) = _NEW_ORDER_PAYLOAD.unpack(payload)
                        self.received_new_orders.append({
                            "client_id": client_id,
                            "order_id": order_id,
                            "side": side,
                            "order_type": otype,
                            "price": price,
                            "quantity": qty,
                        })
                        reply = _encode_partial_fill(
                            order_id=order_id, client_id=client_id, side=side,
                            price=price, executed_price=price,
                            executed_quantity=qty // 2,
                            remaining_quantity=qty - qty // 2,
                            counterparty_order_id=99,
                        )
                        writer.write(reply)
                        await writer.drain()
        except (ConnectionResetError, asyncio.CancelledError):
            pass


@pytest.mark.asyncio
async def test_browser_order_routes_to_gateway_and_report_returns():
    engine = _FakeEngine()
    gw_port = await engine.start()
    ws_port = _free_tcp_port()
    mc_port = _free_udp_port()

    bridge = Bridge(
        multicast_group="239.201.45.67",
        multicast_port=mc_port,
        ws_host="127.0.0.1",
        ws_port=ws_port,
        multicast_interface="127.0.0.1",
        order_gw_host="127.0.0.1",
        order_gw_port=gw_port,
    )
    serve_task = asyncio.create_task(bridge.serve())
    await asyncio.sleep(0.1)

    try:
        async with websockets.connect(f"ws://127.0.0.1:{ws_port}") as ws:
            hello = orjson.loads(await asyncio.wait_for(ws.recv(), timeout=1.0))
            assert hello["type"] == "hello"
            assert hello["orders_enabled"] is True
            client_id = hello["client_id"]
            await asyncio.wait_for(engine.connected.wait(), timeout=1.0)

            await ws.send(orjson.dumps({
                "type": "new_order",
                "client_id": client_id,
                "order_id": 42,
                "side": "BUY",
                "order_type": "LIMIT",
                "price": 100.25,
                "quantity": 10,
            }).decode())

            exec_report = None
            loop = asyncio.get_event_loop()
            deadline = loop.time() + 1.0
            while loop.time() < deadline:
                try:
                    msg = orjson.loads(
                        await asyncio.wait_for(ws.recv(), timeout=0.5)
                    )
                except asyncio.TimeoutError:
                    continue
                if msg.get("type") == "batch" and msg.get("execs"):
                    exec_report = msg["execs"][0]
                    break

            assert exec_report is not None, "exec report never reached WS"
            assert exec_report["report_type"] == "PARTIAL_FILL"
            assert exec_report["order_id"] == 42
            assert exec_report["executed_quantity"] == 5
            assert exec_report["remaining_quantity"] == 5

            assert len(engine.received_new_orders) == 1
            received = engine.received_new_orders[0]
            assert received["price"] == 100_25_000_000
            assert received["side"] == 0           # BUY
            assert received["order_type"] == 0     # LIMIT
    finally:
        serve_task.cancel()
        try:
            await serve_task
        except (asyncio.CancelledError, Exception):
            pass
        await engine.close()


@pytest.mark.asyncio
async def test_order_submission_without_gateway_returns_notice():
    mc_port = _free_udp_port()
    ws_port = _free_tcp_port()
    bridge = Bridge(
        multicast_group="239.201.45.68",
        multicast_port=mc_port,
        ws_host="127.0.0.1",
        ws_port=ws_port,
        multicast_interface="127.0.0.1",
    )
    serve_task = asyncio.create_task(bridge.serve())
    await asyncio.sleep(0.1)
    try:
        async with websockets.connect(f"ws://127.0.0.1:{ws_port}") as ws:
            hello = orjson.loads(await asyncio.wait_for(ws.recv(), timeout=1.0))
            assert hello["orders_enabled"] is False
            await ws.send(orjson.dumps({
                "type": "new_order",
                "client_id": 0,
                "order_id": 1,
                "side": "BUY",
                "order_type": "LIMIT",
                "price": 100.0,
                "quantity": 1,
            }).decode())
            notice = orjson.loads(
                await asyncio.wait_for(ws.recv(), timeout=0.5)
            )
            assert notice["type"] == "notice"
            assert "order gateway" in notice["message"]
    finally:
        serve_task.cancel()
        try:
            await serve_task
        except (asyncio.CancelledError, Exception):
            pass
