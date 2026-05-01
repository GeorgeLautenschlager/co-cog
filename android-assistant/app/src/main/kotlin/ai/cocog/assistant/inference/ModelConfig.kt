package ai.cocog.assistant.inference

import android.content.Context
import java.io.File

/**
 * Configuration for the on-device Gemma 4 E2B model.
 *
 * The model is NOT bundled in the APK (it's ~2 GB). Place the .task file on
 * the device and point [modelPath] at it. Recommended location:
 *   /sdcard/Android/data/ai.cocog.assistant/files/models/gemma4-e2b-it.task
 *
 * To download from the command line:
 *   adb push gemma4-e2b-it.task \
 *       /sdcard/Android/data/ai.cocog.assistant/files/models/gemma4-e2b-it.task
 */
data class ModelConfig(
    val modelPath: String,
    val maxOutputTokens: Int = 512,
    val temperature: Float  = 0.1f,   // low temperature for deterministic transcription
    val topK: Int           = 40,
) {
    companion object {
        private const val MODEL_FILENAME = "gemma4-e2b-it.task"

        /** Returns the first model file found, or null if none is present. */
        fun locate(context: Context): File? {
            val candidates = listOf(
                // App-private external storage — no special permission needed on Android 10+.
                File(context.getExternalFilesDir(null), "models/$MODEL_FILENAME"),
                // Internal storage fallback (use adb push or in-app download).
                File(context.filesDir, "models/$MODEL_FILENAME"),
            )
            return candidates.firstOrNull { it.exists() }
        }
    }
}
