package org.balch.orpheus.djapp.di

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.features.FeatureGraph
import org.balch.orpheus.core.media.ForegroundServiceController
import org.balch.orpheus.core.tempo.GlobalTempo

@DependencyGraph(AppScope::class)
actual interface DjAppGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val globalTempo: GlobalTempo
    val featureGraphFactory: FeatureGraph.Factory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
            @Provides foregroundServiceController: ForegroundServiceController,
        ): DjAppGraph
    }
}
