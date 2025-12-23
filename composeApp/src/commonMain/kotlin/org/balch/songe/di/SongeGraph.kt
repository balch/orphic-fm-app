package org.balch.songe.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.songe.SynthOrchestrator
import org.balch.songe.core.audio.SongeEngine

/**
 * Main dependency graph for the Songe application.
 *
 * This graph aggregates all contributions from @ContributesTo(AppScope::class) interfaces.
 */
@DependencyGraph(AppScope::class)
interface SongeGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator

    @DependencyGraph.Factory
    fun interface Factory {
        /**
         * Create the SongeGraph with the given SongeEngine. The engine is provided at runtime since
         * it's platform-specific.
         */
        fun create(@Provides engine: SongeEngine): SongeGraph
    }
}
