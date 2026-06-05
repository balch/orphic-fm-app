package org.balch.orpheus.core.media

import android.content.Context
import android.media.AudioManager
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robolectric host test for [AudioFocusController]. Drives the REAL
 * OnAudioFocusChangeListener registered with the (shadowed) AudioManager so the
 * focus-event → auto-resume arming (`pausedByTransient`) is exercised end-to-end
 * — the half of the fix that lives in the Android-only listener lambda and is not
 * reachable from the commonMain PlaybackController jvmTest seam.
 *
 * Pinned to SDK 34 via @Config because the project compiles against SDK 37, for
 * which Robolectric has no shadow set. All AudioFocusRequest APIs used here exist
 * since API 26 (minSdk), so SDK 34 exercises identical code paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AudioFocusControllerTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val audioManager
        get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Configurable fake that records the controller's listener callbacks. */
    private class FakeListener(var pauseTransientResult: Boolean) : AudioFocusController.Listener {
        var resumeCount = 0
        var pauseTransientCount = 0
        var lossPermanentCount = 0

        override fun onResumePlayback() { resumeCount++ }
        override fun onPauseTransient(): Boolean {
            pauseTransientCount++
            return pauseTransientResult
        }
        override fun onLossPermanent() { lossPermanentCount++ }
    }

    /** Invoke the controller's registered focus listener directly, synchronously. */
    private fun driveFocus(change: Int) {
        val request = shadowOf(audioManager).lastAudioFocusRequest
        request.listener.onAudioFocusChange(change)
    }

    private fun newController(listener: FakeListener): AudioFocusController =
        AudioFocusController(app).apply {
            setListener(listener)
            assertTrue(request(), "Robolectric grants focus by default")
        }

    @Test
    fun `transient loss while playing then gain auto-resumes`() {
        val listener = FakeListener(pauseTransientResult = true)
        newController(listener)

        driveFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, listener.pauseTransientCount)

        driveFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(
            1,
            listener.resumeCount,
            "a transient loss that interrupted live playback must auto-resume on GAIN",
        )
    }

    // Regression for the [user-pause -> transient-loss -> gain] auto-resume bug:
    // onPauseTransient returns false (we were already user-paused), so the matching
    // GAIN must NOT resume. Under the pre-fix code (pausedByTransient = true set
    // unconditionally) this asserted resumeCount==1 and the bug shipped.
    @Test
    fun `transient loss while already paused does NOT auto-resume on gain`() {
        val listener = FakeListener(pauseTransientResult = false)
        newController(listener)

        driveFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(
            1,
            listener.pauseTransientCount,
            "the listener is still notified of the transient loss",
        )

        driveFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(
            0,
            listener.resumeCount,
            "a transient loss while already paused must NOT arm auto-resume",
        )
    }

    // Regression for the multi-transient bug: a transient loss that interrupts
    // LIVE playback arms auto-resume; a SECOND transient that arrives before any
    // GAIN — while we are already paused by the first — reports it did NOT
    // interrupt live playback (onPauseTransient() returns false) and must NOT
    // disarm the prior arm. Under `pausedByTransient = wasPlaying` the second
    // transient clobbered the armed flag to false and the matching GAIN never
    // resumed; the latch (`if (wasPlaying) pausedByTransient = true`) preserves it.
    @Test
    fun `second transient while paused does not disarm a prior armed auto-resume`() {
        val listener = FakeListener(pauseTransientResult = true)
        newController(listener)

        // Transient #1 interrupts live playback → arms auto-resume.
        driveFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, listener.pauseTransientCount)

        // Transient #2 arrives before any GAIN; we are already paused, so the
        // listener reports it did NOT interrupt live playback.
        listener.pauseTransientResult = false
        driveFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(2, listener.pauseTransientCount)

        // The arm from transient #1 must survive — GAIN auto-resumes.
        driveFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(
            1,
            listener.resumeCount,
            "a second transient while paused must not clobber the armed auto-resume",
        )
    }

    @Test
    fun `gain without a prior transient loss does not resume`() {
        val listener = FakeListener(pauseTransientResult = true)
        newController(listener)

        driveFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(
            0,
            listener.resumeCount,
            "the initial grant delivers no GAIN callback; a bare GAIN must not resume",
        )
    }

    @Test
    fun `permanent loss notifies listener and blocks a later gain from resuming`() {
        val listener = FakeListener(pauseTransientResult = true)
        newController(listener)

        driveFocus(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, listener.lossPermanentCount)

        driveFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(
            0,
            listener.resumeCount,
            "a permanent loss must clear the auto-resume latch",
        )
    }

    @Test
    fun `loss transient can duck is a no-op that never arms resume`() {
        val listener = FakeListener(pauseTransientResult = true)
        newController(listener)

        driveFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0, listener.pauseTransientCount, "CAN_DUCK must not pause")

        driveFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, listener.resumeCount, "CAN_DUCK never paused, so GAIN must not resume")
    }
}
