package org.balch.orpheus.djapp.di

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.features.FeatureGraphHolder
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.djapp.lifecycle.DjAppLifecycleManager
import org.balch.orpheus.features.pulsar.playback.PulsarPlaybackBridge
import org.balch.orpheus.features.pulsar.playback.PulsarSongAdvancer
import org.balch.orpheus.features.pulsar.playback.PulsarSongEnding

@DependencyGraph(AppScope::class)
actual interface DjAppGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val globalTempo: GlobalTempo
    val featureGraphHolder: FeatureGraphHolder
    val mediaSessionManager: MediaSessionManager
    val mediaSessionStateManager: MediaSessionStateManager

    /** Eagerly initialized to register lifecycle callbacks. */
    val djAppLifecycleManager: DjAppLifecycleManager

    /** Eagerly initialized so init {} block subscribes to flows at startup. */
    val playbackController: PlaybackController

    /** Now-playing artwork/title source — read by the home-screen widget. */
    val metadataProducer: MetadataProducer

    /** Eagerly initialized to observe PlaybackController state and drive Pulsar effects. */
    val pulsarPlaybackBridge: PulsarPlaybackBridge

    /** Eagerly initialized so its init {} collectors observe playback/arrangement at startup. */
    val pulsarSongEnding: PulsarSongEnding

    /** Eagerly initialized so its init {} collector subscribes to songEndingEvents at startup. */
    val pulsarSongAdvancer: PulsarSongAdvancer

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): DjAppGraph
    }
}
