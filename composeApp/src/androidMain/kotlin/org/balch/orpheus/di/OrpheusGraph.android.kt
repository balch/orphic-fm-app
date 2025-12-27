package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.SynthOrchestrator
import org.balch.orpheus.core.audio.SynthEngine

/**
 * Android implementation of OrpheusGraph.
 * Actual @DependencyGraph defined here so Metro can see androidMain modules.
 */
@DependencyGraph(AppScope::class)
actual interface OrpheusGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: android.content.Context): OrpheusGraph
    }
}
