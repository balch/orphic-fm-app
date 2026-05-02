package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.pulsar.playback.PulsarPlaybackBridge

@DependencyGraph(AppScope::class)
actual interface DjAppGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val globalTempo: GlobalTempo

    /** Drives PULSAR_PLAYING via PulsarPlaybackBridge (EXPLICIT playback mode). */
    val playbackController: PlaybackController

    /** Eagerly touched in MainViewController so its init {} collector subscribes at startup. */
    val pulsarPlaybackBridge: PulsarPlaybackBridge

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): DjAppGraph
    }
}
