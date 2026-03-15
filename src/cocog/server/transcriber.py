"""Whisper transcription wrapper — runs faster-whisper in a thread executor."""

from __future__ import annotations

import asyncio
import logging
import time

import numpy as np

from cocog.config import AudioConfig, TranscriptionConfig
from cocog.server.transcript import TranscriptBuffer, TranscriptSegment

logger = logging.getLogger("cocog.transcriber")


class Transcriber:
    """Wraps faster-whisper for async transcription of PCM audio chunks."""

    def __init__(
        self,
        transcription_config: TranscriptionConfig,
        audio_config: AudioConfig,
    ):
        self.config = transcription_config
        self.audio_config = audio_config
        self._model = None
        self._session_offset: float = 0.0  # cumulative offset for timestamps

    def load_model(self) -> None:
        """Load the Whisper model. Call once at startup."""
        from faster_whisper import WhisperModel

        logger.info(
            "Loading model",
            extra={
                "model": self.config.model_size,
                "device": self.config.device,
                "compute_type": self.config.compute_type,
            },
        )
        t0 = time.monotonic()
        self._model = WhisperModel(
            self.config.model_size,
            device=self.config.device,
            compute_type=self.config.compute_type,
        )
        elapsed = time.monotonic() - t0
        logger.info("Model loaded", extra={"duration_s": round(elapsed, 2)})

    def _pcm_to_float(self, pcm_bytes: bytes) -> np.ndarray:
        """Convert 16-bit PCM bytes to float32 array in [-1, 1]."""
        samples = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32)
        return samples / 32768.0

    def _transcribe_sync(self, pcm_bytes: bytes) -> list[TranscriptSegment]:
        """Synchronous transcription — runs on executor thread."""
        if self._model is None:
            raise RuntimeError("Model not loaded. Call load_model() first.")

        audio = self._pcm_to_float(pcm_bytes)
        chunk_duration = len(audio) / self.audio_config.sample_rate

        t0 = time.monotonic()
        segments_iter, info = self._model.transcribe(
            audio,
            beam_size=self.config.beam_size,
            language=self.config.language,
        )

        results = []
        for seg in segments_iter:
            results.append(
                TranscriptSegment(
                    start=round(self._session_offset + seg.start, 3),
                    end=round(self._session_offset + seg.end, 3),
                    text=seg.text.strip(),
                    confidence=round(seg.avg_log_prob, 4),
                    transcription_time_s=0.0,  # filled below
                    chunk_duration_s=chunk_duration,
                    wall_clock=time.time(),
                )
            )

        elapsed = time.monotonic() - t0
        rtf = elapsed / chunk_duration if chunk_duration > 0 else 0

        for r in results:
            r.transcription_time_s = round(elapsed, 3)

        self._session_offset += chunk_duration

        logger.info(
            "Transcription complete",
            extra={
                "chunk_duration_s": round(chunk_duration, 2),
                "transcription_time_s": round(elapsed, 3),
                "rtf": round(rtf, 3),
                "segments": len(results),
            },
        )

        return results

    async def run(
        self,
        chunk_queue: asyncio.Queue[bytes],
        transcript: TranscriptBuffer,
    ) -> None:
        """Main loop: consume audio chunks, transcribe, emit segments."""
        loop = asyncio.get_event_loop()
        logger.info("Transcriber started")

        while True:
            pcm_bytes = await chunk_queue.get()
            segments = await loop.run_in_executor(
                None, self._transcribe_sync, pcm_bytes
            )
            for seg in segments:
                transcript.add(seg)
