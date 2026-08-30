package org.balch.orpheus.core.media

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression cover for the Android TV crash loop: a MEDIA_BUTTON intent arriving
 * after an audio-focus teardown killed the app with
 *
 *     IllegalArgumentException: session is already released
 *     at androidx.media3.session.MediaSessionService.onStartCommand
 *
 * Media3's onStartCommand resolves a media-button intent by looking the session
 * up by URI, and when that misses it calls onGetSession() and feeds the result
 * straight to addSession(), which asserts the session is not released. The focus
 * teardown used to release the session out from under the service while the
 * service kept pointing at it, so onGetSession handed back a corpse.
 *
 * These tests drive the real AudioFocusController listener and then call media3's
 * real addSession(), so the assertion under test is media3's own, not a mirror.
 *
 * Pinned to SDK 34 — see [AudioFocusControllerTest] for the rationale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaSessionLifecycleTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val audioManager
        get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainLooper: Looper get() = Looper.getMainLooper()

    /**
     * Real MediaLibraryService so the test exercises media3's addSession guard.
     *
     * The stub notification provider is not decoration: addSession() lazily builds
     * DefaultMediaNotificationProvider, whose static init reads
     * androidx.media3.session.R, which host tests do not have on the classpath.
     * Seeding a provider up front keeps the real addSession path reachable.
     */
    class TestLibraryService : MediaLibraryService() {
        var manager: MediaSessionManager? = null

        override fun onCreate() {
            super.onCreate()
            setMediaNotificationProvider(StubNotificationProvider())
        }

        override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) =
            manager?.librarySession
    }

    private class StubNotificationProvider : MediaNotification.Provider {
        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification = MediaNotification(
            /* notificationId = */ 1,
            NotificationCompat.Builder(RuntimeEnvironment.getApplication(), CHANNEL_ID).build(),
        )

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = false

        override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
            MediaNotification.Provider.NotificationChannelInfo(CHANNEL_ID, "test")

        private companion object {
            const val CHANNEL_ID = "test_channel"
        }
    }

    private class NoopActionHandler(private val wasPlaying: Boolean) : MediaSessionActionHandler {
        override fun onPlay() {}
        override fun onPause() {}
        override fun onStop() {}
        override fun onPauseFromFocusLoss(): Boolean = wasPlaying
    }

    private lateinit var msm: MediaSessionManager
    private lateinit var service: TestLibraryService

    private fun driveFocus(change: Int) {
        shadowOf(audioManager).lastAudioFocusRequest.listener.onAudioFocusChange(change)
    }

    /** Builds a real library session on a real service, then activates + takes focus. */
    private fun activatedWithSession(wasPlaying: Boolean = true) {
        msm = MediaSessionManager(app, AudioFocusController(app))
        service = Robolectric.buildService(TestLibraryService::class.java).create().get()
        service.manager = msm
        service.addSession(msm.buildLibrarySession(service))
        msm.setActionHandler(NoopActionHandler(wasPlaying))
        msm.activate()
        shadowOf(mainLooper).idle()
        msm.requestPlaybackFocus()
        shadowOf(mainLooper).idle()
    }

    /** Stand-in for the legacy caller media3 synthesises for a media-button intent. */
    private fun mediaButtonCaller(): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            app.packageName,
            /* pid = */ 0,
            /* uid = */ 0,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
            /* trusted = */ false,
            Bundle.EMPTY,
            /* isPackageNameVerified = */ false,
        )

    /** Exactly what media3's onStartCommand does for a MEDIA_BUTTON intent. */
    private fun replayMediaButtonStartCommand() {
        val session = assertNotNull(
            service.onGetSession(mediaButtonCaller()),
            "onGetSession must still offer a session after a focus teardown",
        )
        service.addSession(session)
    }

    @AfterTest
    fun tearDown() {
        if (::msm.isInitialized) msm.releaseSession()
        shadowOf(mainLooper).idle()
    }

    @Test
    fun `media button after a permanent focus loss does not hit a released session`() {
        activatedWithSession()
        val built = assertNotNull(msm.librarySession)

        driveFocus(AudioManager.AUDIOFOCUS_LOSS)
        shadowOf(mainLooper).idle()

        assertSame(built, msm.librarySession, "a permanent focus loss must pause, not release")
        replayMediaButtonStartCommand()
    }

    @Test
    fun `media button after the transient watchdog escalates does not hit a released session`() {
        activatedWithSession()
        val built = assertNotNull(msm.librarySession)

        driveFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        // Past the 10-minute deadline: the watchdog escalates to a permanent loss.
        shadowOf(mainLooper).idleFor(11, TimeUnit.MINUTES)

        assertSame(built, msm.librarySession, "the watchdog escalation must pause, not release")
        replayMediaButtonStartCommand()
    }

    @Test
    fun `media button after deactivate does not hit a released session`() {
        activatedWithSession()
        val built = assertNotNull(msm.librarySession)

        msm.deactivate()
        shadowOf(mainLooper).idle()

        assertSame(built, msm.librarySession, "deactivate must not release the service's session")
        replayMediaButtonStartCommand()
    }

    /**
     * Positive control: proves the guard the tests above rely on is media3's own,
     * and pins the exact failure the TV logs showed. A service that cached its own
     * session field would hand this released object back from onGetSession.
     */
    @Test
    fun `media3 rejects a released session - the shape of the TV crash`() {
        activatedWithSession()
        val stale = assertNotNull(msm.librarySession)

        msm.releaseSession()
        shadowOf(mainLooper).idle()

        val error = assertFailsWith<IllegalArgumentException> { service.addSession(stale) }
        assertEquals("session is already released", error.message)
    }

    /**
     * Sleep-timer expiry replay. The StopAll ricochet deactivates the session
     * (clearAll) and Pulsar's Paused state re-activates it milliseconds later,
     * so the service killed by the deactivate reaches onDestroy — and calls
     * releaseSession() — while the manager is active again. The user's next
     * play() must still reach the player.
     */
    @Test
    fun `play after the owning service dies mid-activation reaches the player`() {
        activatedWithSession()
        msm.setServiceIntent(Intent(app, TestLibraryService::class.java))
        msm.updatePlaybackState(true)
        shadowOf(mainLooper).idle()

        msm.deactivate()
        shadowOf(mainLooper).idle()
        msm.activate()
        shadowOf(mainLooper).idle()
        // The stopService() from the deactivate lands now, after the re-activate.
        msm.releaseSession()
        shadowOf(mainLooper).idle()

        // User taps play: PlaybackController.play() calls both of these.
        msm.activate()
        msm.updatePlaybackState(true)
        shadowOf(mainLooper).idle()

        assertNotNull(
            shadowOf(app).nextStartedService,
            "play() must restart the foreground service once the previous one died",
        )
        // The restarted service rebuilds the session in onCreate.
        val restarted = Robolectric.buildService(TestLibraryService::class.java).create().get()
        restarted.manager = msm
        val session = msm.buildLibrarySession(restarted)
        shadowOf(mainLooper).idle()
        assertTrue(
            session.player.playWhenReady,
            "the rebuilt session must come up playing — otherwise media3 never promotes the " +
                "service to the foreground and the tap is silently swallowed",
        )
    }

    @Test
    fun `releaseSession clears the handle so onGetSession offers nothing`() {
        activatedWithSession()

        // The service's onDestroy is the only releaser. Media3 answers a null
        // onGetSession with stopSelfSafely() instead of throwing.
        msm.releaseSession()
        shadowOf(mainLooper).idle()

        assertNull(msm.librarySession, "a released session must not stay reachable")
        assertNull(
            service.onGetSession(mediaButtonCaller()),
            "onGetSession must return null once the session is released",
        )
    }
}
