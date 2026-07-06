package com.cocog.capture.state

enum class CaptureState {
    STOPPED,
    CAPTURING,
    MUTED,
    SUSPENDED,
    THROTTLED
}

sealed class CaptureEvent {
    object Start : CaptureEvent()
    object Stop : CaptureEvent()
    object Mute : CaptureEvent()
    object Unmute : CaptureEvent()
    object MicContentionDetected : CaptureEvent()
    object MicContentionResolved : CaptureEvent()
    object ThermalSevere : CaptureEvent()
    object ThermalRecovered : CaptureEvent()
}

sealed class TransitionResult {
    data class Success(val newState: CaptureState) : TransitionResult()
    data class IllegalTransition(val currentState: CaptureState, val event: CaptureEvent) : TransitionResult()
}

class StateMachine {
    fun transition(currentState: CaptureState, event: CaptureEvent): TransitionResult {
        // D7 & D10: High priority events that work from almost anywhere.
        if (event is CaptureEvent.Stop) return TransitionResult.Success(CaptureState.STOPPED)
        if (event is CaptureEvent.Mute && currentState != CaptureState.STOPPED) {
            return TransitionResult.Success(CaptureState.MUTED)
        }

        val nextState = when (currentState) {
            CaptureState.STOPPED -> when (event) {
                is CaptureEvent.Start -> CaptureState.CAPTURING
                else -> null
            }
            CaptureState.CAPTURING -> when (event) {
                is CaptureEvent.MicContentionDetected -> CaptureState.SUSPENDED
                is CaptureEvent.ThermalSevere -> CaptureState.THROTTLED
                else -> null
            }
            CaptureState.MUTED -> when (event) {
                is CaptureEvent.Unmute -> CaptureState.CAPTURING
                else -> null
            }
            CaptureState.SUSPENDED -> when (event) {
                is CaptureEvent.MicContentionResolved -> CaptureState.CAPTURING
                else -> null
            }
            CaptureState.THROTTLED -> when (event) {
                is CaptureEvent.ThermalRecovered -> CaptureState.CAPTURING
                else -> null
            }
        }

        return if (nextState != null) {
            TransitionResult.Success(nextState)
        } else {
            TransitionResult.IllegalTransition(currentState, event)
        }
    }
}
