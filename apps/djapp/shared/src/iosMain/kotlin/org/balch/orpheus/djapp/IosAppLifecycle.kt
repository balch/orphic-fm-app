package org.balch.orpheus.djapp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.di.StartupRoot
import org.balch.orpheus.core.playback.AudioHostSuspendPolicy
import org.balch.orpheus.core.playback.PlaybackController
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * iOS's foreground signal: gates the UI-only polls and, with playback state, parks the audio host
 * while paused in the background. Seeded from `applicationState` because a widget-intent launch
 * builds the graph in the background.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@SingleIn(AppScope::class)
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<@StartupRoot Any>())
class IosAppLifecycle(
    private val synthEngine: SynthEngine,
    playbackController: PlaybackController,
    scope: AppCoroutineScope,
) {
    private val log = logging("IosAppLifecycle")

    private val _isForeground = MutableStateFlow(
        UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground
    )
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { setForeground(false) }
        center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { setForeground(true) }
        // In a scene-based app the seed can read background on a tap launch and
        // WillEnterForeground may never follow, so becoming active also counts.
        center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { setForeground(true) }

        // setUiVisible is idempotent, so no need to skip the seed emission.
        scope.launch {
            isForeground.collect { synthEngine.setUiVisible(it) }
        }
        AudioHostSuspendPolicy(synthEngine, scope).start(playbackController.state, isForeground)
        log.info { "IosAppLifecycle initialized (foreground=${_isForeground.value})" }
    }

    private fun setForeground(foreground: Boolean) {
        if (_isForeground.value == foreground) return
        log.info { if (foreground) "App foregrounded — resuming UI polling" else "App backgrounded — pausing UI polling" }
        _isForeground.value = foreground
    }
}
