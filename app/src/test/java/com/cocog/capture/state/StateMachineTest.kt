package com.cocog.capture.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StateMachineTest {

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

        // Literal map of the six real (non-global) legal transitions from spec
        val legalTransitions = mapOf(
            CaptureState.STOPPED to listOf(Pair(CaptureEvent.Start, CaptureState.CAPTURING)),
            CaptureState.CAPTURING to listOf(
                Pair(CaptureEvent.MicContentionDetected, CaptureState.SUSPENDED),
                Pair(CaptureEvent.ThermalSevere, CaptureState.THROTTLED)
            ),
            CaptureState.MUTED to listOf(Pair(CaptureEvent.Unmute, CaptureState.CAPTURING)),
            CaptureState.SUSPENDED to listOf(Pair(CaptureEvent.MicContentionResolved, CaptureState.CAPTURING)),
            CaptureState.THROTTLED to listOf(Pair(CaptureEvent.ThermalRecovered, CaptureState.CAPTURING))
        )

        for (state in states) {
            for (event in events) {
                val actual = StateMachine.transition(state, event)
                
                // Determine expected result based on rules
                val expected = when {
                    // Global rule 1: any state + Stop -> STOPPED
                    event is CaptureEvent.Stop -> TransitionResult.Success(CaptureState.STOPPED)
                    
                    // Global rule 2: any state except STOPPED + Mute -> MUTED
                    event is CaptureEvent.Mute && state != CaptureState.STOPPED -> TransitionResult.Success(CaptureState.MUTED)
                    
                    else -> {
                        val transition = legalTransitions[state]?.find { it.first == event }?.second
                        if (transition != null) {
                            TransitionResult.Success(transition)
                        } else {
                            TransitionResult.IllegalTransition(state, event)
                        }
                    }
                }

                assertEquals("Failed for $state + $event", expected, actual)
            }
        }
    }

    @Test
    fun test_no_android_references() {
        // The Test task working directory is the module dir (app/)
        val root = File("src/main/java/com/cocog/capture/state")
        assertTrue("State package directory not found: ${root.absolutePath}", root.exists())

        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEach { line ->
                assertFalse("File ${file.name} contains an android import: $line", line.trim().startsWith("import android"))
            }
        }
    }

    @Test
    fun test_mute_wins_property() {
        // Mute should work from any state except STOPPED (as per StateMachine logic)
        val states = listOf(CaptureState.CAPTURING, CaptureState.MUTED, CaptureState.SUSPENDED, CaptureState.THROTTLED)
        for (state in states) {
            val result = StateMachine.transition(state, CaptureEvent.Mute)
            assertEquals("Expected Success for Mute from $state", TransitionResult.Success(CaptureState.MUTED), result)
        }
    }

    @Test
    fun test_stop_from_any_state() {
        val states = CaptureState.values()
        for (state in states) {
            val result = StateMachine.transition(state, CaptureEvent.Stop)
            assertEquals("Expected Success for Stop from $state", TransitionResult.Success(CaptureState.STOPPED), result)
        }
    }
}
