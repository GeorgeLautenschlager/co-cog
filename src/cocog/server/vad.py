"""Energy-based Voice Activity Detection.

Uses RMS energy of PCM audio to detect speech vs. silence.
No external dependencies — stdlib only.
"""

from __future__ import annotations

import array
import math


def compute_energy(pcm_data: bytes, sample_width: int = 2) -> float:
    """Compute RMS energy of PCM audio data.

    Args:
        pcm_data: Raw PCM bytes (signed 16-bit little-endian).
        sample_width: Bytes per sample (must be 2).

    Returns:
        RMS energy as a float.
    """
    if not pcm_data:
        return 0.0
    if sample_width != 2:
        raise ValueError(f"Only 16-bit (sample_width=2) supported, got {sample_width}")

    samples = array.array("h")
    samples.frombytes(pcm_data)

    if len(samples) == 0:
        return 0.0

    sum_sq = sum(s * s for s in samples)
    return math.sqrt(sum_sq / len(samples))


class EnergyVAD:
    """Detects speech pauses using RMS energy thresholding.

    Tracks how long energy has been below the threshold. Returns True
    from `update()` when silence has persisted long enough AND the
    buffer has a minimum amount of audio (to avoid flushing near-empty buffers).
    """

    def __init__(
        self,
        energy_threshold: float = 500.0,
        silence_duration_s: float = 1.5,
        sample_rate: int = 16000,
        sample_width: int = 2,
        min_buffer_duration_s: float = 2.0,
    ):
        self.energy_threshold = energy_threshold
        self.silence_duration_s = silence_duration_s
        self.sample_rate = sample_rate
        self.sample_width = sample_width
        self.min_buffer_duration_s = min_buffer_duration_s
        self._silence_samples: int = 0

    @property
    def silence_duration(self) -> float:
        """Current accumulated silence duration in seconds."""
        return self._silence_samples / self.sample_rate

    def update(self, pcm_chunk: bytes, buffer_duration_s: float) -> bool:
        """Process a chunk of audio and check for silence pause.

        Args:
            pcm_chunk: Raw PCM bytes for the latest chunk.
            buffer_duration_s: Total duration of audio currently in the buffer.

        Returns:
            True if a silence pause has been detected and buffer is large enough.
        """
        energy = compute_energy(pcm_chunk, self.sample_width)
        num_samples = len(pcm_chunk) // self.sample_width

        if energy < self.energy_threshold:
            self._silence_samples += num_samples
        else:
            self._silence_samples = 0

        return (
            self.silence_duration >= self.silence_duration_s
            and buffer_duration_s >= self.min_buffer_duration_s
        )

    def reset(self) -> None:
        """Reset silence tracking (call after buffer flush)."""
        self._silence_samples = 0
