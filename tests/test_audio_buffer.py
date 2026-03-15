from __future__ import annotations

import asyncio

import pytest

from cocog.config import AudioConfig
from cocog.server.audio_buffer import AudioBuffer


@pytest.fixture
def audio_config():
    return AudioConfig(
        sample_rate=16000,
        channels=1,
        sample_width=2,
        chunk_duration_s=0.5,
        buffer_threshold_s=2.0,
        vad_energy_threshold=500.0,
        vad_silence_duration_s=1.0,
    )


async def test_flush_on_threshold(audio_config, sample_pcm_silence):
    """Buffer should flush when duration threshold is reached."""
    buf = AudioBuffer(audio_config)
    raw_queue: asyncio.Queue[bytes] = asyncio.Queue()
    chunk_queue: asyncio.Queue[bytes] = asyncio.Queue()

    # Generate 2.5s of silence (above 2.0s threshold)
    # Each chunk is 0.5s = 16000 samples * 2 bytes = 16000 bytes
    chunk = sample_pcm_silence(duration_s=0.5)

    # Put 5 chunks (2.5s) into raw_queue
    for _ in range(5):
        await raw_queue.put(chunk)

    # Run buffer in background, let it process
    task = asyncio.create_task(buf.run(raw_queue, chunk_queue))

    try:
        # Should get a flush after threshold is exceeded
        result = await asyncio.wait_for(chunk_queue.get(), timeout=1.0)
        assert len(result) > 0
        # Duration of flushed audio should be ~2.5s worth of bytes
        expected_bytes = int(2.5 * 16000 * 2)
        # Allow for the flush happening at 2.5s (5 chunks)
        assert len(result) >= int(2.0 * 16000 * 2)
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


async def test_flush_on_vad_silence(audio_config, sample_pcm_tone, sample_pcm_silence):
    """Buffer should flush when VAD detects silence after speech."""
    config = AudioConfig(
        sample_rate=16000,
        channels=1,
        sample_width=2,
        chunk_duration_s=0.5,
        buffer_threshold_s=30.0,  # High threshold so only VAD triggers
        vad_energy_threshold=500.0,
        vad_silence_duration_s=1.0,
    )
    buf = AudioBuffer(config)
    raw_queue: asyncio.Queue[bytes] = asyncio.Queue()
    chunk_queue: asyncio.Queue[bytes] = asyncio.Queue()

    # Put speech (tone) for 3s, then silence for 1.5s
    tone_chunk = sample_pcm_tone(freq=440, duration_s=0.5, amplitude=0.5)
    silence_chunk = sample_pcm_silence(duration_s=0.5)

    for _ in range(6):  # 3s of speech
        await raw_queue.put(tone_chunk)
    for _ in range(3):  # 1.5s of silence
        await raw_queue.put(silence_chunk)

    task = asyncio.create_task(buf.run(raw_queue, chunk_queue))

    try:
        result = await asyncio.wait_for(chunk_queue.get(), timeout=1.0)
        assert len(result) > 0
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


async def test_buffer_duration_property(audio_config, sample_pcm_silence):
    """Duration property should reflect accumulated audio."""
    buf = AudioBuffer(audio_config)
    assert buf.duration_s == 0.0

    # Manually add data to buffer (bypass the queue)
    chunk = sample_pcm_silence(duration_s=1.0)
    buf._buffer.extend(chunk)
    assert abs(buf.duration_s - 1.0) < 0.01
