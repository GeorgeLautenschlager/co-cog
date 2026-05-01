package ai.cocog.assistant.memory

import ai.cocog.assistant.inference.ChunkResult
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Async write-ahead queue that flushes [ChunkResult]s to local Markdown files.
 *
 * File layout:
 *   <filesDir>/memory/
 *     YYYY-MM-DD.md   — daily append log
 *     MEMORY.md       — persistent cross-session summary (compaction target)
 *
 * Nothing leaves the device.  Compaction (summarising old daily logs into
 * MEMORY.md) is a future milestone — the files are human-readable in the
 * meantime.
 *
 * Call [enqueue] from any coroutine; it returns immediately.  The backing
 * channel serialises disk writes so only one coroutine ever touches a file.
 */
class MemoryStore(filesDir: File) {

    private val memDir = File(filesDir, "memory").also { it.mkdirs() }
    private val queue  = Channel<ChunkResult>(capacity = Channel.UNLIMITED)

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Non-blocking enqueue.  Does not suspend the caller. */
    fun enqueue(result: ChunkResult) {
        queue.trySend(result)
    }

    /**
     * Drain loop — call once from a long-lived coroutine (e.g. [CoCogApp.appScope]).
     * Suspends until the channel is closed.
     */
    suspend fun drainLoop() = withContext(Dispatchers.IO) {
        for (result in queue) {
            try {
                flush(result)
            } catch (e: Exception) {
                Log.e(TAG, "Memory flush failed for chunk ${result.chunk.index}", e)
            }
        }
    }

    private fun flush(result: ChunkResult) {
        val now  = Date(result.chunk.windowStartMs)
        val day  = dateFmt.format(now)
        val time = timeFmt.format(now)

        val dailyLog = File(memDir, "$day.md")
        dailyLog.appendText(buildEntry(time, result))
        Log.d(TAG, "Flushed chunk ${result.chunk.index} → $day.md")
    }

    private fun buildEntry(time: String, r: ChunkResult): String = buildString {
        append("\n## $time\n\n")
        if (r.transcript.isNotBlank()) {
            append("**Transcript:** ${r.transcript.trim()}\n\n")
        }
        if (r.keyDetails.isNotEmpty()) {
            append("**Key details:**\n")
            r.keyDetails.forEach { append("- $it\n") }
            append("\n")
        }
        if (r.offTaskSignal) {
            append("> ⚠ Off-task signal detected\n\n")
        }
    }

    companion object {
        private const val TAG = "MemoryStore"
    }
}
