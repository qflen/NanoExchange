"""Asyncio UDP multicast feed handler."""

from __future__ import annotations

import asyncio
import socket
import struct
from typing import Callable, Optional

from . import feed


class _FeedProtocol(asyncio.DatagramProtocol):
    def __init__(self, on_message: Callable[[object], None], on_error: Callable[[Exception], None]):
        self._on_message = on_message
        self._on_error = on_error

    def datagram_received(self, data: bytes, addr) -> None:
        try:
            msg = feed.decode_datagram(data)
        except Exception as e:
            self._on_error(e)
            return
        self._on_message(msg)

    def error_received(self, exc: Exception) -> None:
        self._on_error(exc)


class FeedHandler:
    """Joins a multicast group and pumps decoded feed messages into an asyncio.Queue."""

    def __init__(self, group: str, port: int, iface: Optional[str] = None) -> None:
        self.group = group
        self.port = port
        self.iface = iface
        self.queue: asyncio.Queue = asyncio.Queue(maxsize=10_000)
        self._transport: Optional[asyncio.DatagramTransport] = None
        self._errors: list[Exception] = []

    async def start(self) -> None:
        loop = asyncio.get_running_loop()

        # Create a socket explicitly so we can join the multicast group before the asyncio
        # transport gets its hands on it.
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except OSError:
                pass
        sock.bind(("", self.port))
        mreq = struct.pack("=4s4s",
                           socket.inet_aton(self.group),
                           socket.inet_aton(self.iface or "127.0.0.1"))
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        sock.setblocking(False)

        self._transport, _ = await loop.create_datagram_endpoint(
            lambda: _FeedProtocol(self._on_message, self._on_error),
            sock=sock,
        )

    def _on_message(self, msg) -> None:
        try:
            self.queue.put_nowait(msg)
        except asyncio.QueueFull:
            # drop silently; feed consumers treat sequence gaps as the signal to resync
            pass

    def _on_error(self, exc: Exception) -> None:
        self._errors.append(exc)

    def errors(self) -> list[Exception]:
        return list(self._errors)

    async def close(self) -> None:
        if self._transport is not None:
            self._transport.close()
            self._transport = None
