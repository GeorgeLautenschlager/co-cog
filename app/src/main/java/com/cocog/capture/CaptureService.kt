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

/**
 * Foreground microphone-service skeleton.
 *
 * - T1 scope: it must REFUSE to start while half-permissioned (D18) — never touch the
 *   microphone without full consent.
 * - T2 scope: when it *does* go foreground (fully permissioned), it opens an
 *   [AudioRecord] and holds the microphone open for the service's lifetime — no reading,
 *   no processing, no buffering, just holding the mic — and releases it cleanly whenever
 *   the service stops so the OS mic indicator behaves correctly.
 *
 * Real audio capture (draining the record, ring buffer, VAD) arrives in later wave tasks.
 */
class CaptureService : Service() {

    private val metrics: MetricsSink = LogcatMetricsSink()

    /** The held microphone. Non-null only while foregrounded; released on stop. */
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Permissions.hasAll(this)) {
            // Refuse: do not go foreground, do not open the mic, self-stop.
            //
            // Contract (T1): this refusal path assumes the service was started with a
            // plain startService(). It must NOT be launched via startForegroundService()
            // while half-permissioned — that path requires a startForeground() call within
            // ~5s, but a microphone-typed startForeground() itself requires RECORD_AUDIO,
            // so there is no valid foreground call to make when half-permissioned. The
            // onboarding gate enforces this (the Start affordance only appears fully
            // permissioned). T2, which wires the real service start, owns hardening this.
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
        goForeground()
        metrics.event("capture_started")
        return START_STICKY
    }

    override fun onDestroy() {
        // Also covers the mic-error self-stop and any external stopService/stopSelf:
        // onDestroy is the single funnel where the OS tears the service down.
        releaseMicrophone()
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Only after the microphone-typed FGS is established may we lawfully hold the mic.
        holdMicrophone()
    }

    /**
     * Open an [AudioRecord] and start it so the microphone is actively held for the
     * service's lifetime. We never read from it — T2 only holds the mic; draining and
     * processing belong to the later audio-engine task (T7). Idempotent: a redelivered
     * start (START_STICKY) must not stack a second recorder.
     *
     * Format (ledger L1, decided before T2): 16 kHz mono 16-bit PCM from the
     * VOICE_RECOGNITION source — a placeholder until T7 owns the real D2/D3 format.
     */
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

    /** Stop and release the held microphone, if any. Safe to call more than once. */
    private fun releaseMicrophone() {
        val record = audioRecord ?: return
        audioRecord = null
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (e: IllegalStateException) {
            // Best-effort stop; release below still frees the mic.
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("co-cog")
            .setContentText("Capturing")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
    }

    /**
     * Test seam: open the microphone as the foreground path would. Exposed because the
     * full permissioned start can't be reached in an unrooted instrumented test (the D18
     * gate's battery-exemption leg cannot be granted programmatically), so a test grants
     * only RECORD_AUDIO and drives the mic-hold unit directly. The refusal gate itself is
     * covered by [ServiceRefusesHalfPermissionedTest].
     */
    @VisibleForTesting
    fun holdMicrophoneForTest() = holdMicrophone()

    /** Test seam: release the microphone as a service stop would. See [holdMicrophoneForTest]. */
    @VisibleForTesting
    fun releaseMicrophoneForTest() = releaseMicrophone()

    /**
     * Test seam: whether the microphone is currently held. Derived directly from the live
     * instance state ([audioRecord]) rather than a mirrored flag, so it cannot drift out of
     * sync with the actual hold and a START_STICKY restart cannot read stale state.
     */
    @VisibleForTesting
    fun isMicHeld(): Boolean = audioRecord != null

    companion object {
        const val CHANNEL_ID = "cocog.capture"
        const val NOTIFICATION_ID = 1

        // Microphone hold format (ledger L1) — placeholder until T7 owns the real format.
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Set true when the most recent start attempt was refused for being
         * half-permissioned. Exposed so an instrumented test can verify the D18
         * refusal contract without opening the microphone. Single-shot test aid —
         * reflects only the last start; not a general-purpose signal.
         */
        @JvmStatic
        @Volatile
        @VisibleForTesting
        var lastStartRefused: Boolean = false
    }
}
