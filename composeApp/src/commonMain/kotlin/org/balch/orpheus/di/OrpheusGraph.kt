package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.SynthOrchestrator
import org.balch.orpheus.core.audio.SynthEngine

/**
 * Main dependency graph for the Orpheus application.
 *
 * This graph aggregates all contributions from @ContributesTo(AppScope::class) interfaces.
 */
@DependencyGraph(AppScope::class)
interface OrpheusGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator

    @DependencyGraph.Factory
    fun interface Factory {
        /**
         * Create the OrpheusGraph with the given SynthEngine. The engine is provided at runtime since
         * it's platform-specific.
         */
        fun create(@Provides engine: SynthEngine): OrpheusGraph
    }
}
