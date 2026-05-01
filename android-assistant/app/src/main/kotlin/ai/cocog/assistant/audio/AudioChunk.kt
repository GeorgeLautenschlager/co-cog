package ai.cocog.assistant.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One windowed slice of PCM audio ready for model inference.
 *
 * @param index   Monotonically increasing chunk counter.
 * @param samples 16-bit PCM samples at [sampleRate] Hz, mono.
 * @param sampleRate Always 16 000 Hz (model requirement).
 * @param windowStartMs Wall-clock ms at which this window opened.
 */
data class AudioChunk(
    val index: Long,
    val samples: ShortArray,
    val sampleRate: Int = AudioChunker.SAMPLE_RATE,
    val windowStartMs: Long = System.currentTimeMillis(),
) {
    val durationMs: Long get() = (samples.size.toLong() * 1000L) / sampleRate

    /** Little-endian PCM bytes expected by LiteRT-LM audio encoder. */
    fun asByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    // ShortArray does not implement structural equality — provide it explicitly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return index == other.index && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * index.hashCode() + samples.contentHashCode()
}
