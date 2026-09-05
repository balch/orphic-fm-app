package org.balch.orpheus.core.media

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for an app that ships no media service: play() used to stop itself
 * ~13ms later and the app could never sound.
 *
 * doStartService() returned false for two unrelated states — a missing service
 * intent (nothing to start) and a thrown ForegroundServiceStartNotAllowedException
 * (a genuine refusal). updatePlaybackState read both as a refusal and rolled back
 * through rollbackFailedActivation() → handler.onStop().
 *
 * The absent intent is now success, so a service-less app keeps its session, its
 * audio-focus listener and its focus teardown. Asserting on onStop rather than on
 * doStartService's return value pins the behaviour a user hears, not the internal
 * signal that happens to carry it.
 *
 * Pinned to SDK 34 — see [AudioFocusControllerTest] for the rationale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaSessionServicelessPlaybackTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val audioManager
        get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainLooper: Looper get() = Looper.getMainLooper()

    /** Counts the rollback path so a self-inflicted stop is visible. */
    private class FakeActionHandler : MediaSessionActionHandler {
        var stopCount = 0
        var pauseFromFocusLossCount = 0
        override fun onPlay() {}
        override fun onPause() {}
        override fun onStop() { stopCount++ }
        override fun onPauseFromFocusLoss(): Boolean {
            pauseFromFocusLossCount++
            return true
        }
    }

    private fun servicelessManager(handler: FakeActionHandler): MediaSessionManager {
        val msm = MediaSessionManager(app, AudioFocusController(app))
        msm.setActionHandler(handler)
        // Deliberately no setServiceIntent — this is the foreground-only app.
        msm.activate()
        shadowOf(mainLooper).idle()
        return msm
    }

    @Test
    fun `play on an app with no media service does not roll itself back to stopped`() {
        val handler = FakeActionHandler()
        val msm = servicelessManager(handler)

        msm.updatePlaybackState(isPlaying = true)
        shadowOf(mainLooper).idle()

        assertEquals(
            0,
            handler.stopCount,
            "a missing service intent is an opt-out, not a refused FGS start",
        )
    }

    @Test
    fun `an app with no media service still pauses on audio-focus loss`() {
        val handler = FakeActionHandler()
        val msm = servicelessManager(handler)
        assertTrue(msm.requestPlaybackFocus(), "Robolectric grants focus by default")

        msm.updatePlaybackState(isPlaying = true)
        shadowOf(mainLooper).idle()

        // The focus listener must be installed even with no service — otherwise the
        // synth renders straight over a phone call.
        shadowOf(audioManager).lastAudioFocusRequest.listener
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(1, handler.pauseFromFocusLossCount, "the transient pauses playback")
    }
}
