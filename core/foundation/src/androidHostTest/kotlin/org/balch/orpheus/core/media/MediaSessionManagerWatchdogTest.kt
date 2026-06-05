package org.balch.orpheus.core.media

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Robolectric host test for the Android [MediaSessionManager]'s 10-minute
 * transient-loss escalation watchdog — the half of the fix that the
 * AudioFocusController-level test cannot reach.
 *
 * The watchdog is armed in onPauseTransient ONLY when the transient interrupted
 * LIVE playback (wasPlaying). A transient that lands while we are already
 * (user-)paused but still hold focus must NOT arm it — otherwise it would
 * silently escalate to a permanent loss and tear down a session the user is
 * intentionally keeping paused after 10 minutes.
 *
 * Drives the controller's real OnAudioFocusChangeListener and advances
 * Robolectric's paused main looper past the 10-minute deadline to assert whether
 * the escalation (a second onPauseFromFocusLoss + abandon) fires.
 *
 * Pinned to SDK 34 — see [AudioFocusControllerTest] for the rationale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaSessionManagerWatchdogTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val audioManager
        get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainLooper: Looper get() = Looper.getMainLooper()

    /** Fake handler: reports a configurable wasPlaying and counts the focus-loss pauses. */
    private class FakeActionHandler(private val wasPlaying: Boolean) : MediaSessionActionHandler {
        var pauseFromFocusLossCount = 0
        override fun onPlay() {}
        override fun onPause() {}
        override fun onStop() {}
        override fun onPauseFromFocusLoss(): Boolean {
            pauseFromFocusLossCount++
            return wasPlaying
        }
    }

    /** Invoke the controller's registered focus listener directly, synchronously. */
    private fun driveFocus(change: Int) {
        shadowOf(audioManager).lastAudioFocusRequest.listener.onAudioFocusChange(change)
    }

    /** activate() + grant focus, draining the paused looper so isActive/listener are set. */
    private fun activated(handler: FakeActionHandler) {
        val controller = AudioFocusController(app)
        val msm = MediaSessionManager(app, controller)
        msm.setActionHandler(handler)
        msm.activate()
        shadowOf(mainLooper).idle() // run the posted doActivate → isActive = true, listener set
        assertTrue(msm.requestPlaybackFocus(), "Robolectric grants focus by default")
    }

    @Test
    fun `transient loss that interrupts live playback arms the 10-minute escalation watchdog`() {
        val handler = FakeActionHandler(wasPlaying = true)
        activated(handler)

        driveFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, handler.pauseFromFocusLossCount, "the transient pauses once")

        // The watchdog is armed for 10 minutes — advance past it.
        shadowOf(mainLooper).idleFor(11, TimeUnit.MINUTES)

        assertEquals(
            2,
            handler.pauseFromFocusLossCount,
            "the armed watchdog escalates to permanent loss, pausing a second time",
        )
    }

    @Test
    fun `transient loss while already paused does NOT arm the watchdog`() {
        val handler = FakeActionHandler(wasPlaying = false)
        activated(handler)

        driveFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, handler.pauseFromFocusLossCount, "the listener is still notified")

        // No watchdog should be armed; 10+ minutes pass with no escalation.
        shadowOf(mainLooper).idleFor(11, TimeUnit.MINUTES)

        assertEquals(
            1,
            handler.pauseFromFocusLossCount,
            "an unarmed watchdog must not fire — no second pause/escalation",
        )
    }
}
