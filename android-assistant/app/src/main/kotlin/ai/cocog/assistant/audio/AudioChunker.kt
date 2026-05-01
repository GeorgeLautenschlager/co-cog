package ai.cocog.assistant.audio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Converts a stream of raw PCM shorts into fixed-length, overlapping windows.
 *
 * Window layout (all values in samples at 16 kHz):
 *
 *   |←————————— CHUNK_SAMPLES = 320 000 (20 s) —————————→|
 *   |← OVERLAP = 80 000 (5 s) →|←— STEP = 240 000 (15 s) —→|
 *
 * After emitting each chunk the first STEP_SAMPLES are discarded so the next
 * window re-uses the final OVERLAP_SAMPLES, giving a 5-second look-back.
 * The 20-second window sits comfortably inside Gemma 4 E2B's 30-second audio limit.
 *
 * Thread-safety: [write] must be called from a single thread (the capture thread).
 */
class AudioChunker {

    companion object {
        const val SAMPLE_RATE      = 16_000
        const val CHUNK_DURATION_S = 20
        const val OVERLAP_DURATION_S = 5
        const val CHUNK_SAMPLES    = CHUNK_DURATION_S   * SAMPLE_RATE  // 320 000
        const val OVERLAP_SAMPLES  = OVERLAP_DURATION_S * SAMPLE_RATE  // 80 000
        const val STEP_SAMPLES     = CHUNK_SAMPLES - OVERLAP_SAMPLES   // 240 000
    }

    // Channel depth = 4 so the capture thread never blocks the inference thread.
    private val _channel = Channel<AudioChunk>(capacity = 4)
    val chunks: Flow<AudioChunk> = _channel.receiveAsFlow()

    // Sliding window buffer — always holds [0, CHUNK_SAMPLES) samples.
    private val window = ShortArray(CHUNK_SAMPLES)
    private var windowFill = 0       // how many samples are currently valid
    private var chunkIndex = 0L
    private var windowStartMs = System.currentTimeMillis()

    /**
     * Accepts [count] samples from [buf], advances the window, and suspends to
     * emit complete [AudioChunk]s on [chunks].  Must be called from a coroutine.
     */
    suspend fun write(buf: ShortArray, count: Int = buf.size) {
        var src = 0
        while (src < count) {
            val space = CHUNK_SAMPLES - windowFill
            val copy  = minOf(count - src, space)

            buf.copyInto(destination = window, destinationOffset = windowFill,
                         startIndex = src, endIndex = src + copy)
            windowFill += copy
            src        += copy

            if (windowFill == CHUNK_SAMPLES) {
                _channel.send(
                    AudioChunk(
                        index          = chunkIndex++,
                        samples        = window.copyOf(),
                        sampleRate     = SAMPLE_RATE,
                        windowStartMs  = windowStartMs,
                    )
                )
                // Slide: keep the last OVERLAP_SAMPLES, discard the rest.
                window.copyInto(window, destinationOffset = 0,
                                startIndex = STEP_SAMPLES, endIndex = CHUNK_SAMPLES)
                windowFill    = OVERLAP_SAMPLES
                windowStartMs = System.currentTimeMillis()
            }
        }
    }

    fun close() = _channel.close()
}
