package com.cocog.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import com.cocog.capture.state.CaptureEvent
import com.cocog.capture.state.CaptureState
import com.cocog.capture.state.StateMachine
import com.cocog.capture.state.TransitionResult

/**
 * Foreground microphone-service skeleton.
 */
class CaptureService : Service() {

    private val metrics: MetricsSink = LogcatMetricsSink()

    /** The held microphone. Non-null only while foregrounded; released on stop. */
    private var audioRecord: AudioRecord? = null

    /** Current state of the capture session. */
    @get:VisibleForTesting
    var currentState: CaptureState = CaptureState.STOPPED
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle event processing via Intents for testing and future extensibility.
        if (intent?.action == ACTION_PROCESS_EVENT) {
            val eventName = intent.getStringExtra(EXTRA_EVENT_NAME)
            val event = when (eventName) {
                "Start" -> CaptureEvent.Start
                "Stop" -> CaptureEvent.Stop
                "Mute" -> CaptureEvent.Mute
                "Unmute" -> CaptureEvent.Unmute
                "MicContentionDetected" -> CaptureEvent.MicContentionDetected
                "MicContentionResolved" -> CaptureEvent.MicContentionResolved
                "ThermalSevere" -> CaptureEvent.ThermalSevere
                "ThermalRecovered" -> CaptureEvent.ThermalRecovered
                else -> null
            }
            if (event != null) {
                processEvent(event)
            }
            return START_STICKY
        }

        if (!Permissions.hasAll(this)) {
            // Refuse: do not go foreground, do not open the mic, self-stop.
            lastStartRefused = true
            metrics.event(
                "capture_start_refused",
                mapOf(
                    "record_audio" to Permissions.hasRecordAudio(this),
                    "battery_unrestricted" to Permissions.hasUnrestrictedBattery(this),
                ),
            )
            stopSelf()
            return START_NOT_STICKY
        }

        lastStartRefused = false
        // Drive the state machine from STOPPED -> CAPTURING via Start event.
        processEvent(CaptureEvent.Start)
        goForeground()
        metrics.event("capture_started")
        return START_STICKY
    }

    override fun onDestroy() {
        // Also covers the mic-error self-stop and any external stopService/stopSelf:
        processEvent(CaptureEvent.Stop)
        releaseMicrophone()
        super.onDestroy()
    }

    /**
     * Processes a [CaptureEvent], updates the current state, and re-renders the notification.
     */
    fun processEvent(event: CaptureEvent) {
        val result = StateMachine.transition(currentState, event)
        if (result is TransitionResult.Success) {
            currentState = result.newState
            updateNotification()
        }
    }

    private fun goForeground() {
        updateNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        holdMicrophone()
    }

    /** Updates the foreground notification to reflect the current state. */
    private fun updateNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun holdMicrophone() {
        if (audioRecord != null) return

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_ENCODING)
        if (minBuffer <= 0) {
            metrics.event("capture_mic_error", mapOf("reason" to "bad_min_buffer", "value" to minBuffer))
            stopSelf()
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_ENCODING,
            minBuffer,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            metrics.event("capture_mic_error", mapOf("reason" to "not_initialized"))
            stopSelf()
            return
        }

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            metrics.event("capture_mic_error", mapOf("reason" to "not_recording"))
            stopSelf()
            return
        }

        audioRecord = record
        metrics.event("capture_mic_open")
    }

    private fun releaseMicrophone() {
        val record = audioRecord ?: return
        audioRecord = null
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (e: IllegalStateException) {
            metrics.event("capture_mic_error", mapOf("reason" to "stop_failed"))
        }
        record.release()
        metrics.event("capture_mic_closed")
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW),
        )
        val text = when (currentState) {
            CaptureState.STOPPED -> "Stopped"
            CaptureState.CAPTURING -> "Capturing"
            CaptureState.MUTED -> "Muted"
            CaptureState.SUSPENDED -> "Paused — call in progress"
            CaptureState.THROTTLED -> "Paused — device too warm"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("co-cog")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
    }

    @VisibleForTesting
    fun holdMicrophoneForTest() = holdMicrophone()

    @VisibleForTesting
    fun releaseMicrophoneForTest() = releaseMicrophone()

    @VisibleForTesting
    fun isMicHeld(): Boolean = audioRecord != null

    companion object {
        const val CHANNEL_ID = "cocog.capture"
        const val NOTIFICATION_ID = 1
        const val ACTION_PROCESS_EVENT = "com.cocog.capture.ACTION_PROCESS_EVENT"
        const val EXTRA_EVENT_NAME = "extra_event_name"

        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        @JvmStatic
        @Volatile
        @VisibleForTesting
        var lastStartRefused: Boolean = false
    }
}
