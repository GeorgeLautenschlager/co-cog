"""Server entry point: python -m cocog.server"""

from __future__ import annotations

import asyncio
import signal
import sys

from cocog.config import load_config
from cocog.logging_setup import setup_logging
from cocog.server.audio_buffer import AudioBuffer
from cocog.server.transcriber import Transcriber
from cocog.server.transcript import TranscriptBuffer
from cocog.server.ws_receiver import serve


async def run(config_path: str | None = None) -> None:
    cfg = load_config(config_path)
    logger = setup_logging(cfg.server.logging.level)

    logger.info("Starting co-cog server")

    # Initialize components
    transcript = TranscriptBuffer(cfg.server.logging.transcript_dir)

    transcriber = Transcriber(
        transcription_config=cfg.server.transcription,
        audio_config=cfg.server.audio,
    )
    transcriber.load_model()

    audio_buffer = AudioBuffer(cfg.server.audio)

    # Create queues
    raw_queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=1000)
    chunk_queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=50)

    # Run pipeline stages concurrently
    await asyncio.gather(
        serve(cfg.server, raw_queue),
        audio_buffer.run(raw_queue, chunk_queue),
        transcriber.run(chunk_queue, transcript),
    )


def main() -> None:
    config_path = sys.argv[1] if len(sys.argv) > 1 else None
    try:
        asyncio.run(run(config_path))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
