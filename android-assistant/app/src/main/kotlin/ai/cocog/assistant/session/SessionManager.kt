package ai.cocog.assistant.session

import ai.cocog.assistant.inference.ChunkResult
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight, in-memory holder for the current session's state.
 *
 * A session begins when the user states their goals and starts capture, and
 * ends when they stop the service.  Drift detection is simple for now: count
 * consecutive off-task chunks and emit a notification after a threshold.
 */
class SessionManager {

    data class SessionState(
        val active: Boolean            = false,
        val goals: List<String>        = emptyList(),
        val chunksProcessed: Int       = 0,
        val driftCount: Int            = 0,
        val startedAtMs: Long          = 0L,
    )

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun startSession(goals: List<String>) {
        _state.value = SessionState(
            active      = true,
            goals       = goals,
            startedAtMs = System.currentTimeMillis(),
        )
        Log.i(TAG, "Session started. Goals: $goals")
    }

    fun endSession() {
        val s = _state.value
        Log.i(TAG, "Session ended. Processed ${s.chunksProcessed} chunks, " +
                "drift events: ${s.driftCount}")
        _state.value = s.copy(active = false)
    }

    fun currentGoals(): List<String> = _state.value.goals

    fun recordDrift(result: ChunkResult) {
        _state.value = _state.value.let { s ->
            s.copy(
                chunksProcessed = s.chunksProcessed + 1,
                driftCount      = s.driftCount + 1,
            )
        }
    }

    fun recordChunk() {
        _state.value = _state.value.let { s ->
            s.copy(chunksProcessed = s.chunksProcessed + 1)
        }
    }

    /** True when the user has been off-task long enough to warrant a nudge. */
    fun shouldIntervene(): Boolean = _state.value.driftCount >= DRIFT_THRESHOLD

    fun clearDrift() {
        _state.value = _state.value.copy(driftCount = 0)
    }

    companion object {
        private const val TAG            = "SessionManager"
        private const val DRIFT_THRESHOLD = 3  // ~45s of consecutive off-task audio
    }
}
