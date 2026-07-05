package com.cocog.capture

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D18 contract: the capture service must refuse to start while half-permissioned.
 *
 * The test APK is not granted RECORD_AUDIO (a runtime/dangerous permission is never
 * auto-granted), so the app is genuinely half-permissioned. We foreground the app via
 * the onboarding Activity (so a plain startService is allowed on O+), attempt to start
 * the service, and assert it lands in the refusal path rather than going foreground.
 */
@RunWith(AndroidJUnit4::class)
class ServiceRefusesHalfPermissionedTest {

    @Test
    fun serviceRefusesWhenRecordAudioMissing() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Precondition: we really are half-permissioned.
        assertFalse(
            "test environment unexpectedly has RECORD_AUDIO granted",
            Permissions.hasRecordAudio(context),
        )

        ActivityScenario.launch(OnboardingActivity::class.java).use {
            CaptureService.lastStartRefused = false
            context.startService(Intent(context, CaptureService::class.java))

            val refused = waitFor(timeoutMs = 3_000) { CaptureService.lastStartRefused }
            assertTrue("service should refuse to start half-permissioned", refused)
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
}
