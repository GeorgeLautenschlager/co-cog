"""Audio buffer that accumulates PCM and flushes on threshold or VAD silence."""

from __future__ import annotations

import asyncio
import logging
import time

from cocog.config import AudioConfig
from cocog.server.vad import EnergyVAD

logger = logging.getLogger("cocog.audio_buffer")


class AudioBuffer:
    """Accumulates raw PCM audio and produces complete chunks for transcription.

    Flush triggers:
    1. Buffer duration >= buffer_threshold_s
    2. VAD detects silence pause (and buffer has minimum content)
    """

    def __init__(self, config: AudioConfig):
        self.config = config
        self._buffer = bytearray()
        self._vad = EnergyVAD(
            energy_threshold=config.vad_energy_threshold,
            silence_duration_s=config.vad_silence_duration_s,
            sample_rate=config.sample_rate,
            sample_width=config.sample_width,
        )
        self._chunk_count = 0

    @property
    def duration_s(self) -> float:
        """Current buffer duration in seconds."""
        bytes_per_sample = self.config.sample_width * self.config.channels
        return len(self._buffer) / (self.config.sample_rate * bytes_per_sample)

    async def run(
        self,
        raw_queue: asyncio.Queue[bytes],
        chunk_queue: asyncio.Queue[bytes],
    ) -> None:
        """Main loop: consume raw audio, accumulate, flush when ready."""
        logger.info("Audio buffer started")

        while True:
            pcm_data = await raw_queue.get()
            self._buffer.extend(pcm_data)

            # Check flush conditions
            reason = None
            if self.duration_s >= self.config.buffer_threshold_s:
                reason = "threshold"
            elif self._vad.update(pcm_data, self.duration_s):
                reason = "vad_silence"

            if reason is not None:
                await self._flush(chunk_queue, reason)

    async def _flush(self, chunk_queue: asyncio.Queue[bytes], reason: str) -> None:
        """Flush buffer contents to the chunk queue."""
        audio_bytes = bytes(self._buffer)
        duration = self.duration_s
        self._chunk_count += 1

        self._buffer.clear()
        self._vad.reset()

        logger.info(
            "Flush",
            extra={
                "reason": reason,
                "duration_s": round(duration, 2),
                "buffer_depth": chunk_queue.qsize(),
            },
        )

        await chunk_queue.put(audio_bytes)
