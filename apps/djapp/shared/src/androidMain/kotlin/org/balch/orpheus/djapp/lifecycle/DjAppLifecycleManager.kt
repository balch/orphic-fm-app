package org.balch.orpheus.djapp.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.di.StartupRoot
import org.balch.orpheus.core.playback.AudioHostSuspendPolicy
import org.balch.orpheus.core.playback.PlaybackController
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android lifecycle manager for the DJ app.
 *
 * Parks the Oboe stream while paused (see [startHostParking]) and observes
 * activity lifecycle for diagnostic logging. Media-session
 * lifetime is owned by PulsarViewModel via MediaSessionStateManager —
 * this manager intentionally does NOT force the session active/inactive on
 * background, since doing so previously raced with PulsarViewModel's mute
 * collector (dual writers to setPulsarActive) and required force-killing the
 * app to recover. If a "deactivate when backgrounded + muted" policy is ever
 * needed again, route it through PulsarViewModel so there is one writer.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<@StartupRoot Any>())
class DjAppLifecycleManager(
    private val application: Application,
    synthEngine: SynthEngine,
    playbackController: PlaybackController,
    scope: AppCoroutineScope,
) {
    private val log = logging("DjAppLifecycleManager")

    init {
        registerActivityLifecycleCallbacks()
        startHostParking(synthEngine, playbackController, scope)
        log.info { "DjAppLifecycleManager initialized" }
    }

    /**
     * Pause only mutes, so without a park the stream keeps sending silence over A2DP and a
     * Bluetooth headset's tap keeps reading as PAUSE. Resume needs nothing here:
     * PlaybackController.play() calls ensureAudioHostRunning(), which unparks.
     */
    private fun startHostParking(
        synthEngine: SynthEngine,
        playbackController: PlaybackController,
        scope: CoroutineScope,
    ) {
        // Parks on screen too, unlike iOS: Android media apps stop their output on pause, and a
        // headset tap is how an on-screen pause gets resumed. Kept short because A2DP only
        // suspends ~3s after the park, and the headset hears "paused" only then.
        AudioHostSuspendPolicy(synthEngine, scope, grace = 500.milliseconds)
            .start(playbackController.state, foreground = MutableStateFlow(false))
    }

    private fun registerActivityLifecycleCallbacks() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                if (!activity.isChangingConfigurations) {
                    log.info { "App backgrounded — media session lifetime owned by PulsarViewModel" }
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
