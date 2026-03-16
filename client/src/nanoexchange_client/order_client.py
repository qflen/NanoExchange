"""Asyncio TCP order-entry client."""

from __future__ import annotations

import asyncio
import struct
from typing import AsyncIterator, Optional

from . import protocol


class OrderClient:
    """Async TCP client for the NanoExchange order gateway.

    Usage::

        client = OrderClient("127.0.0.1", 9000)
        await client.connect()
        await client.submit_limit(order_id=1, side=Side.BUY, price=100_00000000, quantity=10)
        async for report in client.reports():
            ...
    """

    def __init__(self, host: str, port: int, *, client_id: int = 0) -> None:
        self.host = host
        self.port = port
        self.client_id = client_id
        self._reader: Optional[asyncio.StreamReader] = None
        self._writer: Optional[asyncio.StreamWriter] = None

    async def connect(self) -> None:
        self._reader, self._writer = await asyncio.open_connection(self.host, self.port)
        sock = self._writer.get_extra_info("socket")
        if sock is not None:
            import socket as _s
            sock.setsockopt(_s.IPPROTO_TCP, _s.TCP_NODELAY, 1)

    async def close(self) -> None:
        if self._writer is not None:
            self._writer.close()
            try:
                await self._writer.wait_closed()
            except Exception:
                pass

    async def submit_new_order(
        self, *, order_id: int, side: int, order_type: int, price: int, quantity: int,
        display_size: int = 0, timestamp_nanos: int = 0,
    ) -> None:
        frame = protocol.encode_new_order(
            client_id=self.client_id, order_id=order_id, side=side, order_type=order_type,
            price=price, quantity=quantity, display_size=display_size,
            timestamp_nanos=timestamp_nanos,
        )
        await self._write(frame)

    async def submit_limit(
        self, *, order_id: int, side: int, price: int, quantity: int, timestamp_nanos: int = 0,
    ) -> None:
        await self.submit_new_order(
            order_id=order_id, side=side, order_type=protocol.OrderType.LIMIT,
            price=price, quantity=quantity, timestamp_nanos=timestamp_nanos,
        )

    async def cancel(self, *, order_id: int, timestamp_nanos: int = 0) -> None:
        await self._write(protocol.encode_cancel(order_id=order_id, timestamp_nanos=timestamp_nanos))

    async def modify(
        self, *, order_id: int, new_price: int, new_quantity: int, timestamp_nanos: int = 0,
    ) -> None:
        await self._write(protocol.encode_modify(
            order_id=order_id, new_price=new_price, new_quantity=new_quantity,
            timestamp_nanos=timestamp_nanos,
        ))

    async def heartbeat(self) -> None:
        await self._write(protocol.encode_heartbeat())

    async def _write(self, frame: bytes) -> None:
        assert self._writer is not None
        self._writer.write(frame)
        await self._writer.drain()

    async def reports(self) -> AsyncIterator[protocol.DecodedFrame]:
        """Yield execution reports as they arrive."""
        assert self._reader is not None
        buf = bytearray()
        while True:
            chunk = await self._reader.read(4096)
            if not chunk:
                return
            buf.extend(chunk)
            while True:
                try:
                    frame, consumed = protocol.decode_frame(bytes(buf))
                except ValueError as e:
                    raise ConnectionError(f"protocol error: {e}")
                if frame is None:
                    break
                del buf[:consumed]
                yield frame

    async def read_reports(self, n: int, *, timeout: float = 5.0) -> list[protocol.DecodedFrame]:
        """Read exactly ``n`` reports with a hard timeout. Convenience for tests."""
        out: list[protocol.DecodedFrame] = []
        async def _collect():
            async for r in self.reports():
                out.append(r)
                if len(out) >= n:
                    return
        await asyncio.wait_for(_collect(), timeout=timeout)
        return out
