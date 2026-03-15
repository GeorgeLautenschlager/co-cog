"""WebSocket sender — streams PCM audio to the server with reconnection."""

from __future__ import annotations

import asyncio
import logging

import websockets

from cocog.config import ClientConfig

logger = logging.getLogger("cocog.ws_sender")

# Max frames to buffer during disconnect before dropping
MAX_QUEUE_SIZE = 500


class WebSocketSender:
    """Sends audio frames from a queue to the server over WebSocket."""

    def __init__(self, config: ClientConfig):
        self.config = config

    async def start(self, queue: asyncio.Queue[bytes]) -> None:
        """Connect to server and send audio frames from queue."""
        attempt = 0

        while True:
            try:
                await self._run_connection(queue)
            except (
                websockets.ConnectionClosed,
                ConnectionRefusedError,
                OSError,
            ) as e:
                attempt += 1
                max_attempts = self.config.max_reconnect_attempts
                if max_attempts > 0 and attempt > max_attempts:
                    logger.error(
                        "Max reconnect attempts reached",
                        extra={"error": str(e)},
                    )
                    raise

                delay = min(
                    self.config.reconnect_delay_s * (2 ** (attempt - 1)),
                    30.0,
                )
                logger.warning(
                    "Connection lost, reconnecting",
                    extra={"error": str(e), "delay": delay},
                )
                await asyncio.sleep(delay)

    async def _run_connection(self, queue: asyncio.Queue[bytes]) -> None:
        """Maintain a single WebSocket connection."""
        async with websockets.connect(self.config.server_url) as ws:
            logger.info(
                "Connected",
                extra={"server": self.config.server_url},
            )

            while True:
                pcm_data = await queue.get()
                await ws.send(pcm_data)
