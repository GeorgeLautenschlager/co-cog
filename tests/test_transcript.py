from __future__ import annotations

import json
import time
from pathlib import Path

from cocog.server.transcript import TranscriptBuffer, TranscriptSegment


def test_add_segment_writes_jsonl(tmp_path):
    """Adding a segment should write a JSONL line to the transcript file."""
    buf = TranscriptBuffer(str(tmp_path / "transcripts"))

    seg = TranscriptSegment(
        start=0.0,
        end=3.5,
        text="Hello world",
        confidence=0.95,
        transcription_time_s=0.8,
        chunk_duration_s=5.0,
        wall_clock=time.time(),
    )
    buf.add(seg)

    # Find the written file
    files = list((tmp_path / "transcripts").glob("*.jsonl"))
    assert len(files) == 1

    with open(files[0]) as f:
        lines = f.readlines()
    assert len(lines) == 1

    data = json.loads(lines[0])
    assert data["text"] == "Hello world"
    assert data["start"] == 0.0
    assert data["end"] == 3.5
    assert data["confidence"] == 0.95


def test_add_multiple_segments(tmp_path):
    """Multiple segments should append to the same file."""
    buf = TranscriptBuffer(str(tmp_path / "transcripts"))

    for i in range(3):
        seg = TranscriptSegment(
            start=float(i * 5),
            end=float(i * 5 + 4),
            text=f"Segment {i}",
            confidence=0.9,
            transcription_time_s=0.5,
            chunk_duration_s=5.0,
            wall_clock=time.time(),
        )
        buf.add(seg)

    files = list((tmp_path / "transcripts").glob("*.jsonl"))
    assert len(files) == 1

    with open(files[0]) as f:
        lines = f.readlines()
    assert len(lines) == 3
    assert buf.segments == buf.segments  # sanity
    assert len(buf.segments) == 3


def test_segment_dataclass():
    """TranscriptSegment fields are accessible."""
    seg = TranscriptSegment(
        start=1.0,
        end=2.0,
        text="test",
        confidence=0.99,
        transcription_time_s=0.1,
        chunk_duration_s=3.0,
        wall_clock=1234567890.0,
    )
    assert seg.text == "test"
    assert seg.wall_clock == 1234567890.0
