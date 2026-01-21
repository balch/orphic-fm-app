package org.balch.orpheus.di

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.SynthOrchestrator
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.lifecycle.AndroidAppLifecycleManager
import org.balch.orpheus.core.media.ForegroundServiceController
import org.balch.orpheus.util.ConsoleLogger

/**
 * Android implementation of OrpheusGraph.
 * Actual @DependencyGraph defined here so Metro can see androidMain modules.
 */
@DependencyGraph(AppScope::class)
actual interface OrpheusGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val consoleLogger: ConsoleLogger
    
    /**
     * Android-specific lifecycle manager for background audio handling.
     * Accessing this property ensures it gets initialized.
     */
    val androidAppLifecycleManager: AndroidAppLifecycleManager

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
            @Provides foregroundServiceController: ForegroundServiceController
        ): OrpheusGraph
    }
}
