package com.cocog.capture.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineTest {

    private val stateMachine = StateMachine()

    @Test
    fun test_stop_from_any_state() {
        val states = CaptureState.values()
        for (state in states) {
            val result = stateMachine.transition(state, CaptureEvent.Stop)
            assertTrue("Expected Success for Stop from $state", result is TransitionResult.Success)
            assertEquals(CaptureState.STOPPED, (result as TransitionResult.Success).newState)
        }
    }

    @Test
    fun test_mute_wins_property() {
        val states = listOf(CaptureState.CAPTURING, CaptureState.SUSPENDED, CaptureState.THROTTLED)
        for (state in states) {
            val result = stateMachine.transition(state, CaptureEvent.Mute)
            assertTrue("Expected Success for Mute from $state", result is TransitionResult.Success)
            assertEquals(CaptureState.MUTED, (result as TransitionResult.Success).newState)
        }
    }

    @Test
    fun test_muted_stays_muted_on_other_events() {
        val states = listOf(
            CaptureEvent.MicContentionResolved,
            CaptureEvent.ThermalRecovered,
            CaptureEvent.Start,
            CaptureEvent.MicContentionDetected,
            CaptureEvent.ThermalSevere
        )
        for (event in states) {
            val result = stateMachine.transition(CaptureState.MUTED, event)
            assertTrue("Expected IllegalTransition for $event while MUTED", result is TransitionResult.IllegalTransition)
            assertEquals(CaptureState.MUTED, (result as TransitionResult.IllegalTransition).currentState)
        }
    }

    @Test
    fun test_unmute_from_muted() {
        val result = stateMachine.transition(CaptureState.MUTED, CaptureEvent.Unmute)
        assertTrue("Expected Success for Unmute from MUTED", result is TransitionResult.Success)
        assertEquals(CaptureState.CAPTURING, (result as TransitionResult.Success).newState)
    }

    @Test
    fun test_start_from_stopped() {
        val result = stateMachine.transition(CaptureState.STOPPED, CaptureEvent.Start)
        assertTrue("Expected Success for Start from STOPPED", result is TransitionResult.Success)
        assertEquals(CaptureState.CAPTURING, (result as TransitionResult.Success).newState)
    }

    @Test
    fun test_capture_transitions() {
        // Mic Contention
        val res1 = stateMachine.transition(CaptureState.CAPTURING, CaptureEvent.MicContentionDetected)
        assertTrue(res1 is TransitionResult.Success && res1.newState == CaptureState.SUSPENDED)

        // Thermal Severe
        val res2 = stateMachine.transition(CaptureState.CAPTURING, CaptureEvent.ThermalSevere)
        assertTrue(res2 is TransitionResult.Success && res2.newState == CaptureState.THROTTLED)
    }

    @Test
    fun test_suspended_transitions() {
        val result = stateMachine.transition(CaptureState.SUSPENDED, CaptureEvent.MicContentionResolved)
        assertTrue("Expected Success for MicContentionResolved from SUSPENDED", result is TransitionResult.Success)
        assertEquals(CaptureState.CAPTURING, (result as TransitionResult.Success).newState)
    }

    @Test
    fun test_throttled_transitions() {
        val result = stateMachine.transition(CaptureState.THROTTLED, CaptureEvent.ThermalRecovered)
        assertTrue("Expected Success for ThermalRecovered from THROTTLED", result is TransitionResult.Success)
        assertEquals(CaptureState.CAPTURING, (result as TransitionResult.Success).newState)
    }

    @Test
    fun test_illegal_transitions() {
        // Start from CAPTURING
        val res1 = stateMachine.transition(CaptureState.CAPTURING, CaptureEvent.Start)
        assertTrue(res1 is TransitionResult.IllegalTransition)

        // Unmute from STOPPED
        val res2 = stateMachine.transition(CaptureState.STOPPED, CaptureEvent.Unmute)
        assertTrue(res2 is TransitionResult.IllegalTransition)

        // MicContentionResolved from CAPTURING (it's a no-op if already capturing, but we treat it as illegal transition in our table)
        val res3 = stateMachine.transition(CaptureState.CAPTURING, CaptureEvent.MicContentionResolved)
        assertTrue(res3 is TransitionResult.IllegalTransition)
    }

    @Test
    fun test_no_android_imports() {
        val fileContent = java.io.File("src/main/java/com/cocog/capture/state/CaptureState.kt").readText()
        // Check for any android.* imports
        val hasAndroidImports = "import android.".toRegex().containsMatchIn(fileContent)
        assertTrue("Module contains android.* imports!", !hasAndroidImports)
    }
}
