package com.cocog.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T2 contract: when the capture service goes foreground (the fully-permissioned path),
 * it opens and holds an [android.media.AudioRecord] for its lifetime, and releases it
 * cleanly when it stops.
 *
 * We can't reach the real permissioned start end-to-end in an unrooted instrumented test:
 * the D18 gate ([Permissions.hasAll]) also requires battery-optimization exemption, which
 * cannot be granted programmatically. That gate — the refusal path — is already covered by
 * [ServiceRefusesHalfPermissionedTest]. Here we grant the one dangerous permission the mic
 * needs (RECORD_AUDIO) and drive the mic-hold unit directly through its test seams, proving
 * the actual T2 behaviour: the mic is held while running and released on stop.
 */
@RunWith(AndroidJUnit4::class)
class ServiceHoldsMicrophoneTest {

    @Before
    fun grantRecordAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Grant RECORD_AUDIO to the app under test so AudioRecord genuinely initialises
        // and captures (rather than being silently denied the mic).
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO",
        )
        // Let the grant settle before we touch the microphone.
        waitFor(timeoutMs = 3_000) { Permissions.hasRecordAudio(context) }
        assertTrue(
            "RECORD_AUDIO grant did not take effect",
            Permissions.hasRecordAudio(context),
        )
    }

    @Test
    fun serviceHoldsMicThenReleasesIt() {
        val service = CaptureService()

        CaptureService.micHeld = false
        service.holdMicrophoneForTest()
        assertTrue(
            "service should hold the microphone once foregrounded",
            CaptureService.micHeld,
        )

        service.releaseMicrophoneForTest()
        assertFalse(
            "service should release the microphone when it stops",
            CaptureService.micHeld,
        )
    }

    @Test
    fun holdingMicIsIdempotent() {
        val service = CaptureService()

        CaptureService.micHeld = false
        service.holdMicrophoneForTest()
        // A redelivered start (START_STICKY) must not stack a second recorder; the second
        // hold is a no-op and the mic stays held.
        service.holdMicrophoneForTest()
        assertTrue("mic should still be held after a redundant hold", CaptureService.micHeld)

        // A single release frees it (no leaked recorder from the redundant hold).
        service.releaseMicrophoneForTest()
        assertFalse("one release should fully free the mic", CaptureService.micHeld)
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
