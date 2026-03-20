"""
WebSocket bridge.

Single process, single upstream reader, per-client per-websocket
batching. Threads-of-life:

* **UDP reader** — multicast feed → decode → translator → fan-out into
  each connected client's :class:`Batcher`.
* **WS handler** — runs per connection. On connect, create a batcher,
  an outbox queue, and (if the order gateway is wired) a dedicated
  ``OrderClient`` TCP connection so the browser can submit orders and
  receive its own execution reports.
* **Flush loop** — wakes every 16 ms per client, pulls the latest
  batch from the batcher, enqueues it on the outbox.
* **Writer loop** — drains the outbox onto the WS.
* **Reports loop** (when OrderClient is attached) — reads exec reports
  from the gateway TCP socket and folds them into the session's
  batcher so they ride out on the next flush.

Per-client queues are *bounded* and ``drop-oldest``: a slow client
must not block the fast ones. See ADR-015.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import socket
import struct
from dataclasses import dataclass, field
from typing import Any

import orjson
import websockets

from nanoexchange_client.feed import decode_datagram
from nanoexchange_client.order_client import OrderClient

from .batcher import Batcher
from .translator import (
    order_from_json,
    translate_exec_report,
    translate_feed_message,
)

log = logging.getLogger("nx.bridge")

FLUSH_INTERVAL_S = 0.016  # ~60 Hz
QUEUE_MAX = 64             # outbound frames pending per client


@dataclass(slots=True, eq=False)
class ClientSession:
    """One connected browser. ``eq=False`` keeps the default
    identity-based hash so sessions can go into a ``set``."""
    ws: Any
    client_id: int = 0
    batcher: Batcher = field(default_factory=Batcher)
    outbox: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=QUEUE_MAX))
    degraded: bool = False
    order_client: OrderClient | None = None


class Bridge:
    def __init__(self, *, multicast_group: str, multicast_port: str | int,
                 ws_host: str, ws_port: int,
                 multicast_interface: str | None = None,
                 order_gw_host: str | None = None,
                 order_gw_port: int | None = None) -> None:
        self.multicast_group = multicast_group
        self.multicast_port = int(multicast_port)
        self.ws_host = ws_host
        self.ws_port = ws_port
        # macOS: multicast on loopback requires explicitly naming the
        # interface (127.0.0.1). Linux handles INADDR_ANY. We default to
        # None (INADDR_ANY) and let the caller override for local dev.
        self.multicast_interface = multicast_interface
        # Order gateway is optional: a bridge running in "view-only"
        # mode (public market-data viewer) doesn't need one. When
        # absent, any inbound new_order/cancel_order from the browser
        # gets a reject notice rather than a silent drop.
        self.order_gw_host = order_gw_host
        self.order_gw_port = order_gw_port
        self._clients: set[ClientSession] = set()
        self._next_client_id = 1

    async def serve(self) -> None:
        udp_task = asyncio.create_task(self._run_udp_reader())
        async with websockets.serve(self._handle_ws, self.ws_host, self.ws_port):
            log.info("ws listening on %s:%d", self.ws_host, self.ws_port)
            try:
                await asyncio.Future()
            finally:
                udp_task.cancel()

    # --- UDP side -----------------------------------------------------------

    async def _run_udp_reader(self) -> None:
        loop = asyncio.get_running_loop()
        sock = _make_multicast_socket(
            self.multicast_group, self.multicast_port,
            interface=self.multicast_interface,
        )
        log.info("multicast joined %s:%d (iface=%s)",
                 self.multicast_group, self.multicast_port,
                 self.multicast_interface or "any")
        while True:
            data = await loop.sock_recv(sock, 65_535)
            try:
                decoded = decode_datagram(data)
            except ValueError as exc:
                log.warning("bad datagram: %s", exc)
                continue
            j = translate_feed_message(decoded)
            if j is None:
                continue
            for client in list(self._clients):
                client.batcher.add(j)

    # --- WS side ------------------------------------------------------------

    async def _handle_ws(self, ws) -> None:
        client_id = self._next_client_id
        self._next_client_id += 1
        session = ClientSession(ws=ws, client_id=client_id)
        self._clients.add(session)
        log.info("ws connect id=%d, %d clients total",
                 client_id, len(self._clients))

        order_client: OrderClient | None = None
        reports_task: asyncio.Task | None = None
        if self.order_gw_host is not None and self.order_gw_port is not None:
            try:
                order_client = OrderClient(
                    self.order_gw_host, self.order_gw_port, client_id=client_id,
                )
                await order_client.connect()
                session.order_client = order_client
                reports_task = asyncio.create_task(
                    self._reports_loop(session, order_client)
                )
            except Exception as exc:
                log.warning("order client connect failed: %s", exc)
                order_client = None

        flush_task = asyncio.create_task(self._flush_loop(session))
        writer_task = asyncio.create_task(self._writer_loop(session))

        # Tell the browser its client_id — it uses it to stamp order IDs.
        try:
            await ws.send(orjson.dumps(
                {"type": "hello", "client_id": client_id,
                 "orders_enabled": order_client is not None}
            ).decode())
        except websockets.ConnectionClosed:
            pass

        try:
            async for raw in ws:
                await self._handle_inbound(session, raw)
        except websockets.ConnectionClosed:
            pass
        finally:
            self._clients.discard(session)
            flush_task.cancel()
            writer_task.cancel()
            if reports_task is not None:
                reports_task.cancel()
            if order_client is not None:
                try:
                    await order_client.close()
                except Exception:
                    pass
            log.info("ws disconnect id=%d, %d clients total",
                     client_id, len(self._clients))

    async def _handle_inbound(self, session: ClientSession, raw: Any) -> None:
        try:
            payload = orjson.loads(raw)
        except orjson.JSONDecodeError:
            await _send_notice(session.ws, "malformed JSON")
            return
        msg_type = payload.get("type")
        if msg_type == "new_order":
            if session.order_client is None:
                await _send_notice(session.ws, "order gateway not configured")
                return
            try:
                parsed = order_from_json(payload)
            except (KeyError, ValueError) as exc:
                await _send_notice(session.ws, f"invalid order: {exc}")
                return
            try:
                await session.order_client.submit_new_order(
                    order_id=parsed["order_id"],
                    side=parsed["side"],
                    order_type=parsed["order_type"],
                    price=parsed["price"],
                    quantity=parsed["quantity"],
                    display_size=parsed["display_size"],
                    timestamp_nanos=parsed["timestamp_nanos"],
                )
            except (ConnectionError, OSError) as exc:
                await _send_notice(session.ws, f"order gateway error: {exc}")
        elif msg_type == "cancel_order":
            if session.order_client is None:
                await _send_notice(session.ws, "order gateway not configured")
                return
            try:
                await session.order_client.cancel(
                    order_id=int(payload["order_id"]),
                    timestamp_nanos=int(payload.get("timestamp_nanos", 0)),
                )
            except (ConnectionError, OSError, KeyError) as exc:
                await _send_notice(session.ws, f"cancel failed: {exc}")
        elif msg_type == "ping":
            await _send_notice(session.ws, "pong")
        else:
            await _send_notice(session.ws, f"unknown type: {msg_type!r}")

    async def _reports_loop(
        self, session: ClientSession, order_client: OrderClient,
    ) -> None:
        try:
            async for frame in order_client.reports():
                translated = translate_exec_report(frame)
                if translated is None:
                    continue
                session.batcher.add(translated)
        except asyncio.CancelledError:
            raise
        except ConnectionError as exc:
            log.info("order reports loop ended for id=%d: %s",
                     session.client_id, exc)

    async def _flush_loop(self, session: ClientSession) -> None:
        while True:
            await asyncio.sleep(FLUSH_INTERVAL_S)
            window = session.batcher.flush()
            if window is None:
                continue
            message = window.as_message()
            if session.degraded:
                message["degraded"] = True
                session.degraded = False
            try:
                session.outbox.put_nowait(message)
            except asyncio.QueueFull:
                # Drop oldest, enqueue latest. Mark degraded so the next
                # successful send carries the flag.
                try:
                    session.outbox.get_nowait()
                except asyncio.QueueEmpty:
                    pass
                session.outbox.put_nowait(message)
                session.degraded = True

    async def _writer_loop(self, session: ClientSession) -> None:
        while True:
            message = await session.outbox.get()
            try:
                await session.ws.send(orjson.dumps(message).decode())
            except websockets.ConnectionClosed:
                return


async def _send_notice(ws: Any, text: str) -> None:
    try:
        await ws.send(orjson.dumps(
            {"type": "notice", "message": text}
        ).decode())
    except websockets.ConnectionClosed:
        pass


def _make_multicast_socket(
    group: str, port: int, *, interface: str | None = None,
) -> socket.socket:
    """Create a non-blocking UDP socket joined to the multicast group.

    ``interface`` is the local address of the NIC that should receive
    group traffic. ``None`` → ``INADDR_ANY``, which is what Linux
    usually wants. macOS with loopback-only traffic needs an explicit
    ``'127.0.0.1'``.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", port))
    if interface is None:
        mreq = struct.pack("4sL", socket.inet_aton(group), socket.INADDR_ANY)
    else:
        mreq = struct.pack("4s4s", socket.inet_aton(group),
                           socket.inet_aton(interface))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    sock.setblocking(False)
    return sock


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="nx-bridge")
    p.add_argument("--multicast-group", default="239.200.6.42")
    p.add_argument("--multicast-port", type=int, required=True)
    p.add_argument("--ws-host", default="127.0.0.1")
    p.add_argument("--ws-port", type=int, default=8765)
    p.add_argument("--multicast-interface", default=None,
                   help="Local IP of NIC for multicast (macOS loopback: 127.0.0.1)")
    p.add_argument("--order-gw-host", default=None,
                   help="TCP host of the order gateway; omit to run view-only")
    p.add_argument("--order-gw-port", type=int, default=None)
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    bridge = Bridge(
        multicast_group=args.multicast_group,
        multicast_port=args.multicast_port,
        ws_host=args.ws_host,
        ws_port=args.ws_port,
        multicast_interface=args.multicast_interface,
        order_gw_host=args.order_gw_host,
        order_gw_port=args.order_gw_port,
    )
    asyncio.run(bridge.serve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
