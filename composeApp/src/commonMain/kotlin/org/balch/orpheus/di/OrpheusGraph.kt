package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.SynthOrchestrator
import org.balch.orpheus.core.audio.SynthEngine

/**
 * Main dependency graph for the Orpheus application.
 *
 * This graph aggregates all contributions from @ContributesTo(AppScope::class) interfaces.
 * SynthEngine is fully DI-wired via @ContributesBinding from platform implementations.
 */
@DependencyGraph(AppScope::class)
interface OrpheusGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator
    val synthEngine: SynthEngine
}
