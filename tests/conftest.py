from __future__ import annotations

import array
import math
import struct

import pytest

from cocog.config import AppConfig, load_config


@pytest.fixture
def test_config(tmp_path):
    """Return an AppConfig with defaults and a temp transcript dir."""
    import yaml

    cfg_path = tmp_path / "config.yaml"
    cfg_data = {
        "server": {
            "host": "127.0.0.1",
            "port": 9999,
            "audio": {
                "sample_rate": 16000,
                "channels": 1,
                "sample_width": 2,
                "chunk_duration_s": 0.5,
                "buffer_threshold_s": 5.0,
                "vad_energy_threshold": 500.0,
                "vad_silence_duration_s": 1.0,
            },
            "transcription": {
                "model_size": "tiny",
                "device": "cpu",
                "compute_type": "int8",
                "beam_size": 1,
                "language": "en",
            },
            "logging": {
                "level": "DEBUG",
                "transcript_dir": str(tmp_path / "transcripts"),
            },
        },
        "client": {
            "server_url": "ws://127.0.0.1:9999/audio",
            "sample_rate": 16000,
            "channels": 1,
            "chunk_duration_s": 0.5,
            "reconnect_delay_s": 0.1,
            "max_reconnect_attempts": 3,
            "logging": {"level": "DEBUG"},
        },
    }
    with open(cfg_path, "w") as f:
        yaml.dump(cfg_data, f)
    return load_config(cfg_path)


@pytest.fixture
def sample_pcm_silence():
    """Generate silent PCM bytes (all zeros)."""

    def _generate(duration_s: float = 1.0, sample_rate: int = 16000) -> bytes:
        num_samples = int(duration_s * sample_rate)
        return b"\x00\x00" * num_samples

    return _generate


@pytest.fixture
def sample_pcm_tone():
    """Generate a sine wave tone as PCM bytes."""

    def _generate(
        freq: float = 440.0,
        duration_s: float = 1.0,
        sample_rate: int = 16000,
        amplitude: float = 0.5,
    ) -> bytes:
        num_samples = int(duration_s * sample_rate)
        samples = array.array("h")  # signed 16-bit
        for i in range(num_samples):
            t = i / sample_rate
            val = int(amplitude * 32767 * math.sin(2 * math.pi * freq * t))
            samples.append(max(-32768, min(32767, val)))
        return samples.tobytes()

    return _generate
