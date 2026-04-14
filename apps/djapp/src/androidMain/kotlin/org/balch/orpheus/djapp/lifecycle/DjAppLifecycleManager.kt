package org.balch.orpheus.djapp.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.plugin.symbols.APP_URI

/**
 * Android lifecycle manager for the DJ app.
 *
 * When the app is backgrounded while Pulsar is playing, the foreground service
 * keeps audio alive. When backgrounded while paused (muted), the media session
 * is deactivated so the foreground service stops — no point keeping a notification
 * for a silent backgrounded app.
 */
@SingleIn(AppScope::class)
@Inject
class DjAppLifecycleManager(
    private val application: Application,
    private val synthEngine: SynthEngine,
    private val mediaSessionStateManager: MediaSessionStateManager,
) {
    private val log = logging("DjAppLifecycleManager")

    init {
        registerActivityLifecycleCallbacks()
        log.info { "DjAppLifecycleManager initialized" }
    }

    private fun registerActivityLifecycleCallbacks() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                if (!activity.isChangingConfigurations) {
                    onAppBackgrounded()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun onAppBackgrounded() {
        // If no audio source needs the media session, nothing to tear down.
        if (!mediaSessionStateManager.isMediaSessionNeeded.value) {
            log.debug { "App backgrounded — media session already inactive" }
            return
        }

        // Check if the app-level mute is active. When muted, Pulsar isn't
        // producing audio — the media session was only kept alive so the
        // notification Play button remained functional. In the background,
        // we don't need that keepalive.
        val isMuted = synthEngine.getPluginPort(APP_URI, "muted")?.asBoolean() ?: true

        if (isMuted) {
            log.info { "App backgrounded while muted — deactivating media session" }
            mediaSessionStateManager.setPulsarActive(false)
        } else {
            log.info { "App backgrounded while playing — keeping media session for background audio" }
        }
    }
}
