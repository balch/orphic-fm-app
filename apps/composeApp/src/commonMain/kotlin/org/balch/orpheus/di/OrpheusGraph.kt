package org.balch.orpheus.di

import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.util.ConsoleLogger

/**
 * Main dependency graph for the Orpheus application.
 * 
 * This is an expect interface - each platform provides its own actual
 * @DependencyGraph implementation that can see platform-specific modules.
 */
expect interface OrpheusGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator
    val synthEngine: SynthEngine
    val consoleLogger: ConsoleLogger
    val globalTempo: GlobalTempo
}
