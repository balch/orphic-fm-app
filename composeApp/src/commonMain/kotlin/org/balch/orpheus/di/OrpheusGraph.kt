package org.balch.orpheus.di

import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.SynthOrchestrator
import org.balch.orpheus.core.audio.SynthEngine

/**
 * Main dependency graph for the Orpheus application.
 * 
 * This is an expect interface - each platform provides its own actual
 * @DependencyGraph implementation that can see platform-specific modules.
 */
expect interface OrpheusGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator
    val synthEngine: SynthEngine
}
