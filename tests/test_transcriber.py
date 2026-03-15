from __future__ import annotations

import array
import math
from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from cocog.config import AudioConfig, TranscriptionConfig
from cocog.server.transcriber import Transcriber
from cocog.server.transcript import TranscriptBuffer


@pytest.fixture
def transcriber():
    return Transcriber(
        transcription_config=TranscriptionConfig(
            model_size="tiny",
            device="cpu",
            compute_type="int8",
            beam_size=1,
            language="en",
        ),
        audio_config=AudioConfig(),
    )


def test_pcm_to_float(transcriber):
    """PCM bytes should convert to float32 in [-1, 1]."""
    # Max positive 16-bit value
    pcm = array.array("h", [32767, -32768, 0]).tobytes()
    result = transcriber._pcm_to_float(pcm)

    assert result.dtype == np.float32
    assert len(result) == 3
    assert abs(result[0] - 1.0) < 0.001
    assert abs(result[1] - (-1.0)) < 0.001
    assert result[2] == 0.0


def test_transcribe_sync_with_mock(transcriber):
    """Transcription should produce TranscriptSegments from mocked model."""
    # Create a mock segment
    mock_segment = MagicMock()
    mock_segment.start = 0.0
    mock_segment.end = 2.5
    mock_segment.text = " Hello, this is a test."
    mock_segment.avg_log_prob = -0.15

    mock_info = MagicMock()
    mock_model = MagicMock()
    mock_model.transcribe.return_value = ([mock_segment], mock_info)

    transcriber._model = mock_model

    # Generate 1s of PCM
    pcm = b"\x00\x00" * 16000  # 1s of silence

    segments = transcriber._transcribe_sync(pcm)

    assert len(segments) == 1
    assert segments[0].text == "Hello, this is a test."
    assert segments[0].start == 0.0
    assert segments[0].end == 2.5
    assert segments[0].transcription_time_s >= 0
    assert segments[0].chunk_duration_s == 1.0


def test_transcribe_updates_session_offset(transcriber):
    """Session offset should advance after each transcription."""
    mock_model = MagicMock()
    mock_model.transcribe.return_value = ([], MagicMock())
    transcriber._model = mock_model

    pcm_1s = b"\x00\x00" * 16000  # 1s
    pcm_2s = b"\x00\x00" * 32000  # 2s

    transcriber._transcribe_sync(pcm_1s)
    assert abs(transcriber._session_offset - 1.0) < 0.01

    transcriber._transcribe_sync(pcm_2s)
    assert abs(transcriber._session_offset - 3.0) < 0.01


def test_transcribe_without_model_raises(transcriber):
    """Calling transcribe before load_model should raise."""
    with pytest.raises(RuntimeError, match="Model not loaded"):
        transcriber._transcribe_sync(b"\x00\x00" * 16000)
