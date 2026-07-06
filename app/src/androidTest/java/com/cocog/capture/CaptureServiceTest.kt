package com.cocog.capture

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun grantPermissions() {
        // Grant POST_NOTIFICATIONS for API 33+ via shell command to ensure it works in instrumented tests.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
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

    private fun assertNotificationText(expectedText: String) {
        val activeNotifications = notificationManager.activeNotifications
        var foundMatch = false
        for (notification in activeNotifications) {
            if (notification.id == CaptureService.NOTIFICATION_ID) {
                val actualText = notification.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                if (actualText == expectedText) {
                    foundMatch = true
                    break
                }
            }
        }
        assertEquals("Expected notification text to be '$expectedText'", true, foundMatch)
    }

    private fun assertNoNotification() {
        val activeNotifications = notificationManager.activeNotifications
        for (notification in activeNotifications) {
            if (notification.id == CaptureService.NOTIFICATION_ID) {
                throw AssertionError("Expected no notification with ID ${CaptureService.NOTIFICATION_ID}, but found one.")
            }
        }
    }
}
