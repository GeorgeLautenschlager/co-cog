"""WebSocket server — receives binary PCM audio frames from clients."""

from __future__ import annotations

import asyncio
import logging

import websockets

from cocog.config import ServerConfig

logger = logging.getLogger("cocog.ws_receiver")


async def handle_connection(
    ws: websockets.WebSocketServerProtocol,
    raw_queue: asyncio.Queue[bytes],
) -> None:
    """Handle a single client connection — receive binary frames into queue."""
    client = f"{ws.remote_address[0]}:{ws.remote_address[1]}"
    logger.info("Client connected", extra={"client": client})
    frame_count = 0

    try:
        async for message in ws:
            if isinstance(message, bytes):
                await raw_queue.put(message)
                frame_count += 1
                if frame_count % 100 == 0:
                    logger.debug(
                        "Frames received",
                        extra={
                            "frame_id": frame_count,
                            "bytes": len(message),
                            "buffer_depth": raw_queue.qsize(),
                        },
                    )
            else:
                logger.warning("Received text frame, expected binary")
    except websockets.ConnectionClosed as e:
        logger.info(
            "Client disconnected",
            extra={"client": client, "error": str(e)},
        )
    finally:
        logger.info(
            "Connection closed",
            extra={"client": client, "frame_id": frame_count},
        )


async def serve(
    config: ServerConfig,
    raw_queue: asyncio.Queue[bytes],
) -> None:
    """Start the WebSocket server and accept connections."""
    async def handler(ws: websockets.WebSocketServerProtocol, path: str = "/") -> None:
        await handle_connection(ws, raw_queue)

    logger.info(
        "Server listening",
        extra={"host": config.host, "port": config.port},
    )

    async with websockets.serve(handler, config.host, config.port):
        await asyncio.Future()  # run forever
