package ai.cocog.assistant.inference

import ai.cocog.assistant.audio.AudioChunk
import android.util.Log
import com.google.ai.edge.litert.lm.InferenceModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Wraps the LiteRT-LM [InferenceModel] for Gemma 4 E2B.
 *
 * ## SDK integration note
 * Add to app/build.gradle.kts:
 *   implementation("com.google.ai.edge.litert:litert-lm:1.0.0-alpha05")
 *
 * LiteRT-LM differs from the ML Kit GenAI Prompt API in two key ways:
 *   1. It runs inside background services (no Activity context required).
 *   2. It exposes raw InferenceModel rather than a managed session, giving us
 *      control over token budgets and streaming callbacks.
 *
 * ## Audio input
 * Gemma 4 E2B encodes audio natively; the SDK accepts raw PCM via
 * [InferenceModel.addAudioChunk] before [generateResponse].  The API surface
 * stabilized after alpha-03; verify method signatures against the version in
 * use and update the `// SDK call` comments below accordingly.
 */
class LiteRTEngine {

    private var model: InferenceModel? = null
    private val mutex = Mutex()         // serialize inference calls

    // Publicly observable state.
    @Volatile var loadState: LoadState = LoadState.Idle
        private set

    sealed class LoadState {
        object Idle    : LoadState()
        object Loading : LoadState()
        data class Ready(val modelPath: String) : LoadState()
        data class Error(val cause: Throwable)  : LoadState()
    }

    // -----------------------------------------------------------------------
    // Model lifecycle
    // -----------------------------------------------------------------------

    /**
     * Loads the model from [config.modelPath].  Safe to call from any thread;
     * blocks until loading completes.  Idempotent — ignores repeated calls
     * once [LoadState.Ready].
     */
    suspend fun load(config: ModelConfig) {
        if (loadState is LoadState.Ready) return
        mutex.withLock {
            if (loadState is LoadState.Ready) return
            loadState = LoadState.Loading
            Log.i(TAG, "Loading model from ${config.modelPath}")
            try {
                // SDK call — InferenceModel.Options is the builder in litert-lm 1.0.0.
                val options = InferenceModel.Options.Builder()
                    .setModelPath(config.modelPath)
                    .setMaxTokens(config.maxOutputTokens)
                    .setTemperature(config.temperature)
                    .setTopK(config.topK)
                    .build()
                model = InferenceModel.create(options)
                loadState = LoadState.Ready(config.modelPath)
                Log.i(TAG, "Model loaded — ${config.modelPath}")
            } catch (t: Throwable) {
                loadState = LoadState.Error(t)
                Log.e(TAG, "Model load failed", t)
                throw t
            }
        }
    }

    fun isLoaded(): Boolean = loadState is LoadState.Ready

    fun close() {
        model?.close()
        model = null
        loadState = LoadState.Idle
    }

    // -----------------------------------------------------------------------
    // Inference
    // -----------------------------------------------------------------------

    /**
     * Runs a single transcription + triage pass on [chunk].
     *
     * The prompt asks the model to output JSON so we can parse it
     * deterministically without a second round-trip.
     */
    suspend fun processChunk(chunk: AudioChunk, goals: List<String>): ChunkResult {
        val m = model ?: error("Model not loaded")
        val t0 = System.currentTimeMillis()

        val prompt = buildPrompt(goals)
        val raw: String = mutex.withLock {
            // SDK call — feed PCM audio then generate.
            // InferenceModel.addAudioChunk accepts little-endian 16-bit PCM at 16 kHz.
            m.addAudioChunk(chunk.asByteArray(), chunk.sampleRate)
            m.generateResponse(prompt)
        }

        val inferenceMs = System.currentTimeMillis() - t0
        return parseResponse(raw, chunk, inferenceMs)
    }

    // -----------------------------------------------------------------------
    // Prompt construction
    // -----------------------------------------------------------------------

    private fun buildPrompt(goals: List<String>): String {
        val goalBlock = if (goals.isEmpty()) "No specific goals set."
                        else goals.joinToString("\n") { "• $it" }
        return """
<start_of_turn>user
You are a focus assistant for someone with ADHD. Your job is to transcribe ambient audio and identify details worth capturing.

Session goals:
$goalBlock

For the audio just provided:
1. Transcribe what was said, faithfully.
2. Extract key facts, decisions, names, numbers, or action items.
3. Flag if the conversation seems off-task relative to the goals.

Reply with valid JSON only — no prose, no markdown fences:
{
  "transcript": "<verbatim transcription>",
  "key_details": ["<detail 1>", "<detail 2>"],
  "worth_remembering": <true|false>,
  "off_task_signal": <true|false>
}<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }

    // -----------------------------------------------------------------------
    // Response parsing
    // -----------------------------------------------------------------------

    private fun parseResponse(raw: String, chunk: AudioChunk, inferenceMs: Long): ChunkResult {
        // Trim any extra whitespace or token-sampling artifacts before the JSON.
        val jsonStr = raw.trim().let {
            val start = it.indexOf('{')
            val end   = it.lastIndexOf('}')
            if (start != -1 && end != -1) it.substring(start, end + 1) else it
        }

        return try {
            val obj = JSONObject(jsonStr)
            val details = buildList {
                val arr = obj.optJSONArray("key_details")
                if (arr != null) {
                    for (i in 0 until arr.length()) add(arr.getString(i))
                }
            }
            ChunkResult(
                chunk            = chunk,
                transcript       = obj.optString("transcript", ""),
                keyDetails       = details,
                worthRemembering = obj.optBoolean("worth_remembering", false),
                offTaskSignal    = obj.optBoolean("off_task_signal", false),
                rawResponse      = raw,
                inferenceMs      = inferenceMs,
            )
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed — raw: ${raw.take(200)}", e)
            ChunkResult(
                chunk       = chunk,
                transcript  = raw.trim(),
                rawResponse = raw,
                inferenceMs = inferenceMs,
            )
        }
    }

    companion object {
        private const val TAG = "LiteRTEngine"
    }
}
