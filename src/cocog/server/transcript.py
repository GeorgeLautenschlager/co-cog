"""Structured transcript output — JSONL file + console logging."""

from __future__ import annotations

import json
import logging
import time
from dataclasses import asdict, dataclass
from pathlib import Path

logger = logging.getLogger("cocog.transcript")


@dataclass
class TranscriptSegment:
    start: float
    end: float
    text: str
    confidence: float
    transcription_time_s: float
    chunk_duration_s: float
    wall_clock: float  # epoch time when segment was produced


class TranscriptBuffer:
    """Collects transcript segments and writes them to JSONL + console."""

    def __init__(self, transcript_dir: str):
        self.transcript_dir = Path(transcript_dir)
        self.segments: list[TranscriptSegment] = []
        self._session_start = time.time()
        self._file_path: Path | None = None

    def _ensure_file(self) -> Path:
        if self._file_path is None:
            self.transcript_dir.mkdir(parents=True, exist_ok=True)
            ts = time.strftime("%Y%m%d_%H%M%S", time.localtime(self._session_start))
            self._file_path = self.transcript_dir / f"session_{ts}.jsonl"
        return self._file_path

    def add(self, segment: TranscriptSegment) -> None:
        """Add a segment — logs to console and appends to JSONL file."""
        self.segments.append(segment)

        logger.info(
            "Segment",
            extra={
                "start": round(segment.start, 2),
                "end": round(segment.end, 2),
                "text": segment.text,
                "confidence": round(segment.confidence, 3),
            },
        )

        self._write_to_file(segment)

    def _write_to_file(self, segment: TranscriptSegment) -> None:
        path = self._ensure_file()
        with open(path, "a") as f:
            f.write(json.dumps(asdict(segment)) + "\n")
