package com.cocog.capture.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StateMachineTest {

    private val stateMachine = StateMachine()

    @Test
    fun test_exhaustive_transitions() {
        val states = CaptureState.values()
        val events = listOf(
            CaptureEvent.Start,
            CaptureEvent.Stop,
            CaptureEvent.Mute,
            CaptureEvent.Unmute,
            CaptureEvent.MicContentionDetected,
            CaptureEvent.MicContentionResolved,
            CaptureEvent.ThermalSevere,
            CaptureEvent.ThermalRecovered
        )

        for (state in states) {
            for (event in events) {
                val expected = getExpectedResult(state, event)
                val actual = stateMachine.transition(state, event)
                assertEquals("Failed for $state + $event", expected, actual)
            }
        }
    }

    private fun getExpectedResult(state: CaptureState, event: CaptureEvent): TransitionResult {
        // Global rules first
        if (event is CaptureEvent.Stop) return TransitionResult.Success(CaptureState.STOPPED)
        if (event is CaptureEvent.Mute && state != CaptureState.STOPPED) return TransitionResult.Success(CaptureState.MUTED)

        return when (state) {
            CaptureState.STOPPED -> when (event) {
                is CaptureEvent.Start -> TransitionResult.Success(CaptureState.CAPTURING)
                else -> TransitionResult.IllegalTransition(state, event)
            }
            CaptureState.CAPTURING -> when (event) {
                is CaptureEvent.MicContentionDetected -> TransitionResult.Success(CaptureState.SUSPENDED)
                is CaptureEvent.ThermalSevere -> TransitionResult.Success(CaptureState.THROTTLED)
                else -> TransitionResult.IllegalTransition(state, event)
            }
            CaptureState.MUTED -> when (event) {
                is CaptureEvent.Unmute -> TransitionResult.Success(CaptureState.CAPTURING)
                else -> TransitionResult.IllegalTransition(state, event)
            }
            CaptureState.SUSPENDED -> when (event) {
                is CaptureEvent.MicContentionResolved -> TransitionResult.Success(CaptureState.CAPTURING)
                else -> TransitionResult.IllegalTransition(state, event)
            }
            CaptureState.THROTTLED -> when (event) {
                is CaptureEvent.ThermalRecovered -> TransitionResult.Success(CaptureState.CAPTURING)
                else -> TransitionResult.IllegalTransition(state, event)
            }
        }
    }

    @Test
    fun test_no_android_references() {
        // The Test task working directory is the module dir (app/)
        val root = File("src/main/java/com/cocog/capture/state")
        assertTrue("State package directory not found: ${root.absolutePath}", root.exists())

        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val content = file.readText()
            assertFalse("File ${file.name} contains 'android.' reference!", content.contains("android."))
        }
    }

    @Test
    fun test_mute_wins_property() {
        // Mute should work from any state except STOPPED (as per StateMachine logic)
        val states = listOf(CaptureState.CAPTURING, CaptureState.MUTED, CaptureState.SUSPENDED, CaptureState.THROTTLED)
        for (state in states) {
            val result = stateMachine.transition(state, CaptureEvent.Mute)
            assertEquals("Expected Success for Mute from $state", TransitionResult.Success(CaptureState.MUTED), result)
        }
    }

    @Test
    fun test_stop_from_any_state() {
        val states = CaptureState.values()
        for (state in states) {
            val result = stateMachine.transition(state, CaptureEvent.Stop)
            assertEquals("Expected Success for Stop from $state", TransitionResult.Success(CaptureState.STOPPED), result)
        }
    }
}
