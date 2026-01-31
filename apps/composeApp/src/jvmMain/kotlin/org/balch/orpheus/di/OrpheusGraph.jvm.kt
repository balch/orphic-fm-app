package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.SynthOrchestrator
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.util.ConsoleLogger

/**
 * JVM implementation of OrpheusGraph.
 * Actual @DependencyGraph defined here so Metro can see jvmMain modules.
 */
@DependencyGraph(AppScope::class)
actual interface OrpheusGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val consoleLogger: ConsoleLogger
    actual val globalTempo: GlobalTempo

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): OrpheusGraph
    }
}
