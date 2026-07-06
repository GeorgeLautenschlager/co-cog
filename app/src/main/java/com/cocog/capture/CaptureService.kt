package com.cocog.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
 *
 * - T1 scope: it must REFUSE to start while half-permissioned (D18) — never touch the
 *   microphone without full consent.
 * - T2 scope: when it *does* go foreground (fully permissioned), it opens an
 *   [AudioRecord] and holds the microphone open for the service's lifetime — no reading,
 *   no processing, no buffering, just holding the mic — and releases it cleanly whenever
 *   the service stops so the OS mic indicator behaves correctly.
 * - T4 scope: the persistent notification's text always reflects [currentState] (D17 —
 *   the notification must never lie). [processEvent] is the one production entry point
 *   that drives the T3 state machine and re-renders the notification in the same call;
 *   later tasks (T11 mute, T14 contention, T15 thermal) call it directly with their real
 *   events. Real audio capture (draining the record, ring buffer, VAD) arrives in later
 *   wave tasks.
 */
class CaptureService : Service() {

    private val metrics: MetricsSink = LogcatMetricsSink()

    /** The held microphone. Non-null only while foregrounded; released on stop. */
    private var audioRecord: AudioRecord? = null

    /**
     * The capture session's current state (T3's [CaptureState]). Starts `STOPPED`; only
     * [processEvent] may advance it, by driving [StateMachine.transition].
     */
    @get:VisibleForTesting
    var currentState: CaptureState = CaptureState.STOPPED
        private set

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
        // Drive the state machine from STOPPED -> CAPTURING before going foreground, so
        // buildNotification() already renders CAPTURING's text by the time goForeground()
        // reads it. Note this means processEvent's own notify() briefly posts the
        // notification first; goForeground()'s startForeground() re-posts the same id right
        // after (same content) to make the promotion to a real foreground service — harmless,
        // just not literally "the first post", despite how that might read at a glance.
        processEvent(CaptureEvent.Start)
        goForeground()
        metrics.event("capture_started")
        return START_STICKY
    }

    override fun onDestroy() {
        // Also covers the mic-error self-stop and any external stopService/stopSelf:
        // onDestroy is the single funnel where the OS tears the service down.
        processEvent(CaptureEvent.Stop)
        releaseMicrophone()
        super.onDestroy()
    }

    /**
     * Processes a [CaptureEvent] through [StateMachine.transition] and, on a legal
     * transition, updates [currentState] and re-renders the notification in this same call
     * (D17: within 1 s of any transition — synchronous, so trivially satisfied). Illegal
     * transitions are silently ignored (T3's contract: e.g. MUTED is a hard sink). This is
     * the one production entry point later tasks (T11 mute, T14 contention, T15 thermal)
     * call directly with their real events — not a test-only stub.
     *
     * The STOPPED target is special: a stopped/tearing-down service isn't foreground and
     * has no notification to keep truthful, so instead of rendering "Stopped" we cancel the
     * standing notification (see [cancelNotification]) rather than leave a stale one behind.
     *
     * Both branches below are guarded by [baseContext] being non-null: a real Android-launched
     * instance always has one (the framework attaches it before `onCreate()`), but a bare
     * `CaptureService()` built directly in a test does not, and any [getSystemService] call on
     * such an instance throws `NullPointerException`. [ServiceHoldsMicrophoneTest]'s
     * `onDestroyReleasesMic` relies on `onDestroy()` — and therefore this method, via the
     * `Stop` event — being callable on exactly such a context-less instance, so neither branch
     * may touch `getSystemService` when there's no context to back it.
     */
    fun processEvent(event: CaptureEvent) {
        val result = StateMachine.transition(currentState, event)
        if (result is TransitionResult.Success) {
            currentState = result.newState
            if (baseContext == null) return
            if (currentState == CaptureState.STOPPED) {
                cancelNotification()
            } else {
                updateNotification()
            }
        }
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
     * Re-renders the foreground notification to reflect [currentState]. Called once via
     * [goForeground] (which uses [startForeground] itself for the initial promotion) and
     * thereafter by [processEvent] on every non-STOPPED transition — subsequent updates use
     * plain [NotificationManager.notify], the idiomatic pattern once a service is already
     * foreground (no need to re-call [startForeground] for content-only changes).
     */
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Clears the notification when the machine reaches STOPPED. Without this, a notification
     * posted via [updateNotification]'s plain `notify()` would linger after STOPPED (nothing
     * else cancels it), which would both lie about the true state (D17) and leave a stale
     * "Capturing"-or-whatever notification visible.
     */
    private fun cancelNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
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
        // T4 (ledger L1): text per state — D17 requires this to always match currentState;
        // exact wording is a UX placeholder a later task can restyle without touching the
        // binding logic above.
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

    /**
     * Test seam (T4): attach a real [Context] to a manually-constructed instance so
     * notification APIs (`getSystemService`) work. The Android framework normally calls
     * [attachBaseContext] before `onCreate()`; a bare `CaptureService()` built directly in a
     * test has none, and `attachBaseContext` is `protected` on `ContextWrapper` — this just
     * exposes it. Does not run in, and is not called from, any production path.
     */
    @VisibleForTesting
    fun attachContextForTest(context: Context) = attachBaseContext(context)

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
