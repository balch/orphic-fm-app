package org.balch.orpheus.features.pulsar.playback

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleEvent
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol

/**
 * Bridges PlaybackController state to Pulsar-side effects without creating a
 * DI cycle.
 *
 * Why this exists: PulsarViewModel (which IS PulsarFeature) cannot inject
 * PlaybackController directly because PlaybackController's constructor
 * resolves MetadataProducer → PulsarMetadataProducer → PulsarFeature →
 * PulsarViewModel. That circular chain stack-overflows Metro at app launch.
 *
 * This bridge breaks the cycle by NOT depending on PulsarFeature. It writes
 * the C++ PULSAR_PLAYING port directly via SynthController, and listens for
 * StopAll lifecycle events independently of PulsarViewModel.
 */
@SingleIn(AppScope::class)
@Inject
class PulsarPlaybackBridge(
    private val playbackController: PlaybackController,
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val synthController: SynthController,
    private val scope: AppCoroutineScope,
) {
    private val log = logging("PulsarPlaybackBridge")

    init {
        // PlaybackController.state drives:
        //   - setPulsarActive (keeps MediaSession alive while playing/paused)
        //   - PULSAR_PLAYING port (gates beat generation in C++ — saves CPU when off)
        scope.launch {
            playbackController.state.collect { state ->
                mediaSessionStateManager.setPulsarActive(state != PlaybackState.Stopped)
                synthController.setPluginControl(
                    id = PulsarSymbol.PLAYING.controlId,
                    value = IntValue(if (state == PlaybackState.Playing) 1 else 0),
                )
            }
        }

        // StopAll (timer expiry, etc.) → ask the controller to pause. Routing
        // through the controller keeps state consistent with the rest of the
        // architecture.
        scope.launch {
            playbackLifecycleManager.events.collect { event ->
                if (event is PlaybackLifecycleEvent.StopAll) {
                    log.debug { "Received StopAll — pausing via PlaybackController" }
                    playbackController.pause()
                }
            }
        }
    }
}
