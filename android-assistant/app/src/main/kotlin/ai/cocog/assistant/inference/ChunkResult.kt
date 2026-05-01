package ai.cocog.assistant.inference

import ai.cocog.assistant.audio.AudioChunk

/**
 * Output of a single audio-chunk inference pass.
 *
 * @param chunk           The source audio window.
 * @param transcript      Verbatim transcription of the chunk.
 * @param keyDetails      Notable facts, decisions, or action items extracted.
 * @param worthRemembering True if any [keyDetails] should be flushed to memory.
 * @param offTaskSignal   True if the content seems unrelated to current goals.
 * @param rawResponse     Unparsed model output — preserved for debugging.
 * @param inferenceMs     Wall-clock milliseconds spent in model inference.
 */
data class ChunkResult(
    val chunk: AudioChunk,
    val transcript: String,
    val keyDetails: List<String>   = emptyList(),
    val worthRemembering: Boolean  = false,
    val offTaskSignal: Boolean     = false,
    val rawResponse: String        = "",
    val inferenceMs: Long          = 0L,
)
