from __future__ import annotations

from cocog.server.vad import compute_energy, EnergyVAD


def test_compute_energy_silence(sample_pcm_silence):
    """Silence should have zero energy."""
    pcm = sample_pcm_silence(duration_s=0.1)
    assert compute_energy(pcm) == 0.0


def test_compute_energy_tone(sample_pcm_tone):
    """A tone should have significant energy."""
    pcm = sample_pcm_tone(freq=440, duration_s=0.1, amplitude=0.5)
    energy = compute_energy(pcm)
    assert energy > 1000  # Well above any silence threshold


def test_compute_energy_empty():
    """Empty data should return 0."""
    assert compute_energy(b"") == 0.0


def test_vad_detects_silence(sample_pcm_silence):
    """VAD should detect a pause after sustained silence."""
    vad = EnergyVAD(
        energy_threshold=500.0,
        silence_duration_s=1.0,
        sample_rate=16000,
        min_buffer_duration_s=2.0,
    )
    chunk_duration = 0.5
    chunk = sample_pcm_silence(duration_s=chunk_duration)

    # Feed 2s of silence with 3s buffer duration (above min)
    for i in range(4):  # 4 * 0.5s = 2s of silence
        buffer_dur = 3.0  # Pretend buffer has 3s of audio
        result = vad.update(chunk, buffer_dur)

    # After 2s of silence with 3s buffer, should trigger
    assert result is True


def test_vad_no_trigger_with_speech(sample_pcm_tone):
    """VAD should not trigger during active speech (tone)."""
    vad = EnergyVAD(
        energy_threshold=500.0,
        silence_duration_s=1.0,
        sample_rate=16000,
        min_buffer_duration_s=2.0,
    )
    chunk = sample_pcm_tone(freq=440, duration_s=0.5, amplitude=0.5)

    for i in range(10):
        result = vad.update(chunk, buffer_duration_s=5.0)

    assert result is False


def test_vad_min_buffer_guard(sample_pcm_silence):
    """VAD should not trigger if buffer is below minimum duration."""
    vad = EnergyVAD(
        energy_threshold=500.0,
        silence_duration_s=0.5,
        sample_rate=16000,
        min_buffer_duration_s=2.0,
    )
    chunk = sample_pcm_silence(duration_s=0.5)

    # Feed silence but report small buffer
    for i in range(4):
        result = vad.update(chunk, buffer_duration_s=1.0)

    assert result is False


def test_vad_reset(sample_pcm_silence):
    """Reset should clear silence tracking."""
    vad = EnergyVAD(
        energy_threshold=500.0,
        silence_duration_s=1.0,
        sample_rate=16000,
        min_buffer_duration_s=2.0,
    )
    chunk = sample_pcm_silence(duration_s=1.0)
    vad.update(chunk, buffer_duration_s=3.0)
    assert vad.silence_duration > 0

    vad.reset()
    assert vad.silence_duration == 0.0
