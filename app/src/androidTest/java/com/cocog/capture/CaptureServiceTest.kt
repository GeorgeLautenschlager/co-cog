package com.cocog.capture

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T4 contract: [CaptureService.processEvent] keeps the persistent notification's text in
 * sync with the T3 state machine (D17). Drives all 5 states through the real production
 * method on a bare, context-attached instance (see [CaptureService.attachContextForTest]) —
 * not through a real permissioned `startForegroundService()` start, since the D18 gate's
 * battery-exemption leg can't be granted programmatically in an unrooted instrumented test
 * (same constraint [ServiceHoldsMicrophoneTest] documents). That means this test does not
 * exercise `goForeground()`'s own `startForeground()` call directly — [ServiceHoldsMicrophoneTest]
 * already covers `goForeground`/mic-hold; this test is scoped to the state→notification binding.
 */
@RunWith(AndroidJUnit4::class)
class CaptureServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun grantPermissions() {
        // Grant POST_NOTIFICATIONS for API 33+ via shell command to ensure it works in instrumented tests.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            )
            // Let the grant settle before the test posts anything (same pattern as
            // ServiceHoldsMicrophoneTest's RECORD_AUDIO grant).
            waitFor(timeoutMs = 3_000) {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private inline fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    @Test
    fun testAllStatesReflectedInNotification() {
        val service = CaptureService()
        service.attachContextForTest(context)

        // Initial state is STOPPED, no notification should be present (per fix B2).
        assertNoNotification()

        // Start -> CAPTURING
        service.processEvent(com.cocog.capture.state.CaptureEvent.Start)
        assertNotificationText("Capturing")

        // Mute
        service.processEvent(com.cocog.capture.state.CaptureEvent.Mute)
        assertNotificationText("Muted")

        // Unmute -> CAPTURING
        service.processEvent(com.cocog.capture.state.CaptureEvent.Unmute)
        assertNotificationText("Capturing")

        // MicContentionDetected -> SUSPENDED
        service.processEvent(com.cocog.capture.state.CaptureEvent.MicContentionDetected)
        assertNotificationText("Paused — call in progress")

        // MicContentionResolved -> CAPTURING
        service.processEvent(com.cocog.capture.state.CaptureEvent.MicContentionResolved)
        assertNotificationText("Capturing")

        // ThermalSevere -> THROTTLED
        service.processEvent(com.cocog.capture.state.CaptureEvent.ThermalSevere)
        assertNotificationText("Paused — device too warm")

        // ThermalRecovered -> CAPTURING
        service.processEvent(com.cocog.capture.state.CaptureEvent.ThermalRecovered)
        assertNotificationText("Capturing")

        // Stop -> STOPPED (no notification should be present)
        service.processEvent(com.cocog.capture.state.CaptureEvent.Stop)
        assertNoNotification()
    }

    /**
     * `notify()`/`cancel()` cross a binder call to the system server; `activeNotifications`
     * reads back from it and isn't guaranteed to reflect a call that just returned. Poll
     * (same `waitFor` pattern as the permission grant above) instead of asserting
     * synchronously, so this doesn't flake on a slower device — this also matches D17's own
     * "within 1 s" framing.
     */
    private fun assertNotificationText(expectedText: String) {
        var lastSeenTexts: List<String?> = emptyList()
        val matched = waitFor(timeoutMs = 1_000) {
            val texts = notificationManager.activeNotifications
                .filter { it.id == CaptureService.NOTIFICATION_ID }
                .map { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() }
            lastSeenTexts = texts
            texts.any { it == expectedText }
        }
        assertTrue(
            "Expected notification text '$expectedText', last saw $lastSeenTexts",
            matched,
        )
    }

    private fun assertNoNotification() {
        val cleared = waitFor(timeoutMs = 1_000) {
            notificationManager.activeNotifications.none { it.id == CaptureService.NOTIFICATION_ID }
        }
        assertTrue(
            "Expected no notification with ID ${CaptureService.NOTIFICATION_ID}, but one was still active",
            cleared,
        )
    }
}
