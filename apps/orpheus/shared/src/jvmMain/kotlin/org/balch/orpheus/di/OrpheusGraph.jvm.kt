package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.ai.ControlHighlightEventBus
import org.balch.orpheus.features.pulsar.playback.PulsarSongAdvancer
import org.balch.orpheus.features.pulsar.playback.PulsarSongEnding
import org.balch.orpheus.util.ConsoleLogger

/**
 * JVM implementation of OrpheusGraph.
 * Actual @DependencyGraph defined here so Metro can see jvmMain modules.
 */
@DependencyGraph(AppScope::class)
actual interface OrpheusGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val synthController: SynthController
    actual val consoleLogger: ConsoleLogger
    actual val globalTempo: GlobalTempo
    actual val controlHighlightEventBus: ControlHighlightEventBus

    /** Eagerly touched in main.kt so its init {} collectors observe playback/arrangement at startup. */
    val pulsarSongEnding: PulsarSongEnding

    /** Eagerly touched in main.kt so its init {} collector subscribes to songEndingEvents at startup. */
    val pulsarSongAdvancer: PulsarSongAdvancer

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): OrpheusGraph
    }
}
