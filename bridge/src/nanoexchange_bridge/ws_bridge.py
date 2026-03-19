"""
WebSocket bridge.

Single process, single upstream reader, per-client per-websocket
batching. Threads-of-life:

* **UDP reader** — multicast feed → decode → translator → fan-out into
  each connected client's :class:`Batcher`.
* **TCP reader** (optional) — maintains an ``OrderClient`` for each
  browser that is *also* submitting orders. A plain viewer doesn't need
  one. Exec reports that come back are routed to the originating WS.
* **WS handler** — runs per connection. On connect, create a batcher
  and a queue. The flush task wakes every 16 ms, pulls the latest
  batch, writes it. On disconnect, clean up.

Per-client queues are *bounded* and ``drop-oldest``: a slow client must
not block the fast ones. Slice 9's spec: drop oldest and mark the
client degraded. We surface "degraded" in an application-level message
so the UI can show a warning banner.
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

from .batcher import Batcher
from .translator import translate_feed_message

log = logging.getLogger("nx.bridge")

FLUSH_INTERVAL_S = 0.016  # ~60 Hz
QUEUE_MAX = 64             # outbound frames pending per client


@dataclass(slots=True, eq=False)
class ClientSession:
    """One connected browser. ``eq=False`` keeps the default
    identity-based hash so sessions can go into a ``set``."""
    ws: Any
    batcher: Batcher = field(default_factory=Batcher)
    outbox: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=QUEUE_MAX))
    degraded: bool = False


class Bridge:
    def __init__(self, *, multicast_group: str, multicast_port: str | int,
                 ws_host: str, ws_port: int,
                 multicast_interface: str | None = None) -> None:
        self.multicast_group = multicast_group
        self.multicast_port = int(multicast_port)
        self.ws_host = ws_host
        self.ws_port = ws_port
        # macOS: multicast on loopback requires explicitly naming the
        # interface (127.0.0.1). Linux handles INADDR_ANY. We default to
        # None (INADDR_ANY) and let the caller override for local dev.
        self.multicast_interface = multicast_interface
        self._clients: set[ClientSession] = set()

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
        session = ClientSession(ws=ws)
        self._clients.add(session)
        log.info("ws connect, %d clients total", len(self._clients))
        flush_task = asyncio.create_task(self._flush_loop(session))
        writer_task = asyncio.create_task(self._writer_loop(session))
        try:
            async for _raw in ws:
                # Inbound orders from the browser are out of scope for the
                # batcher — would need an OrderClient per browser. Slice 10+.
                # For now, politely echo a notice.
                await ws.send(orjson.dumps(
                    {"type": "notice", "message": "order entry not wired yet"}
                ).decode())
        except websockets.ConnectionClosed:
            pass
        finally:
            self._clients.discard(session)
            flush_task.cancel()
            writer_task.cancel()
            log.info("ws disconnect, %d clients total", len(self._clients))

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
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    bridge = Bridge(
        multicast_group=args.multicast_group,
        multicast_port=args.multicast_port,
        ws_host=args.ws_host,
        ws_port=args.ws_port,
        multicast_interface=args.multicast_interface,
    )
    asyncio.run(bridge.serve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
