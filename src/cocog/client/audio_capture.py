"""Microphone audio capture using sounddevice."""

from __future__ import annotations

import asyncio
import logging

from cocog.config import ClientConfig

logger = logging.getLogger("cocog.audio_capture")


class AudioCapture:
    """Captures audio from the default microphone and pushes PCM chunks to a queue."""

    def __init__(self, config: ClientConfig):
        self.config = config
        self._chunk_samples = int(config.sample_rate * config.chunk_duration_s)

    async def start(self, queue: asyncio.Queue[bytes]) -> None:
        """Start capturing audio and putting PCM chunks on the queue."""
        import sounddevice as sd

        loop = asyncio.get_event_loop()

        def callback(indata, frames, time_info, status):
            if status:
                logger.warning("Audio capture status: %s", status)
            # indata is a numpy array of shape (frames, channels), dtype float32
            # Convert to 16-bit PCM
            import numpy as np

            pcm = (indata[:, 0] * 32767).astype(np.int16).tobytes()
            loop.call_soon_threadsafe(queue.put_nowait, pcm)

        logger.info(
            "Capture started",
            extra={
                "sample_rate": self.config.sample_rate,
                "chunk_size_bytes": self._chunk_samples * 2,
            },
        )

        stream = sd.InputStream(
            samplerate=self.config.sample_rate,
            channels=self.config.channels,
            dtype="float32",
            blocksize=self._chunk_samples,
            callback=callback,
        )

        with stream:
            # Keep running until cancelled
            while True:
                await asyncio.sleep(1.0)
