package com.cocog.capture

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun testAllStatesReflectedInNotification() {
        val intent = Intent(context, CaptureService::class.java)
        context.startForegroundService(intent)

        // Wait for service to start and reach CAPTURING state
        Thread.sleep(1000)

        assertNotificationText("Capturing")

        sendEvent("Mute")
        Thread.sleep(500)
        assertNotificationText("Muted")

        sendEvent("Unmute")
        Thread.sleep(500)
        assertNotificationText("Capturing")

        sendEvent("MicContentionDetected")
        Thread.sleep(500)
        assertNotificationText("Paused — call in progress")

        sendEvent("MicContentionResolved")
        Thread.sleep(500)
        assertNotificationText("Capturing")

        sendEvent("ThermalSevere")
        Thread.sleep(500)
        assertNotificationText("Paused — device too warm")

        sendEvent("ThermalRecovered")
        Thread.sleep(500)
        assertNotificationText("Capturing")

        // Stop the service
        context.stopService(intent)
    }

    private fun sendEvent(eventName: String) {
        val intent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_PROCESS_EVENT
            putExtra(CaptureService.EXTRA_EVENT_NAME, eventName)
        }
        context.startForegroundService(intent)
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
}
