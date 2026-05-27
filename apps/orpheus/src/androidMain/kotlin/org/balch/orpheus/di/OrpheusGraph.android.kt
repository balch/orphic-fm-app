package org.balch.orpheus.di

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.lifecycle.AndroidAppLifecycleManager
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.ai.ControlHighlightEventBus
import org.balch.orpheus.features.pulsar.playback.PulsarPlaybackBridge
import org.balch.orpheus.features.pulsar.playback.PulsarSongAdvancer
import org.balch.orpheus.features.pulsar.playback.PulsarSongEnding
import org.balch.orpheus.util.ConsoleLogger

/**
 * Android implementation of OrpheusGraph.
 * Actual @DependencyGraph defined here so Metro can see androidMain modules.
 */
@DependencyGraph(AppScope::class)
actual interface OrpheusGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val synthController: SynthController
    actual val consoleLogger: ConsoleLogger
    actual val globalTempo: GlobalTempo
    actual val controlHighlightEventBus: ControlHighlightEventBus
    val mediaSessionManager: MediaSessionManager
    val mediaSessionStateManager: MediaSessionStateManager

    /**
     * Android-specific lifecycle manager for background audio handling.
     * Accessing this property ensures it gets initialized.
     */
    val androidAppLifecycleManager: AndroidAppLifecycleManager

    /** Eagerly initialized so init {} block subscribes to flows at startup. */
    val playbackController: PlaybackController

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
        ): OrpheusGraph
    }
}
