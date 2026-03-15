"""Client entry point: python -m cocog.client"""

from __future__ import annotations

import asyncio
import sys

from cocog.config import load_config
from cocog.logging_setup import setup_logging
from cocog.client.audio_capture import AudioCapture
from cocog.client.ws_sender import WebSocketSender


async def run(config_path: str | None = None) -> None:
    cfg = load_config(config_path)
    logger = setup_logging(cfg.client.logging.level)

    logger.info("Starting co-cog client")

    capture = AudioCapture(cfg.client)
    sender = WebSocketSender(cfg.client)

    queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=500)

    await asyncio.gather(
        capture.start(queue),
        sender.start(queue),
    )


def main() -> None:
    config_path = sys.argv[1] if len(sys.argv) > 1 else None
    try:
        asyncio.run(run(config_path))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
