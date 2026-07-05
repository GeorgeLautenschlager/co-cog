package com.cocog.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.VisibleForTesting

/**
 * Foreground microphone-service skeleton. T1 scope: it must REFUSE to start while
 * half-permissioned (D18) — never touch the microphone without full consent. Actual
 * audio capture (AudioRecord, ring buffer, VAD) arrives in later wave tasks.
 */
class CaptureService : Service() {

    private val metrics: MetricsSink = LogcatMetricsSink()

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

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

    companion object {
        const val CHANNEL_ID = "cocog.capture"
        const val NOTIFICATION_ID = 1

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
