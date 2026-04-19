package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.tempo.GlobalTempo

/**
 * JVM implementation of DjAppGraph.
 * Actual @DependencyGraph defined here so Metro can see jvmMain modules.
 *
 * Known benign warning at build time:
 *   [Metro/SuspiciousUnusedMultibinding] Set<FeaturePanel> has N source bindings
 *   but no consumer at AppScope.
 * The Set is consumed by HeaderViewModel inside the child ViewModelGraph
 * (FeatureScope); Metro 1.0.0-RC2's analyzer aggregates at AppScope and
 * doesn't trace into the child graph's consumers. Behavior is correct —
 * `@Suppress` can't silence this particular diagnostic (it's emitted via
 * the K2 compiler's diagnosticReporter, not a standard Kotlin warning).
 */
@DependencyGraph(AppScope::class)
actual interface DjAppGraph : ViewModelGraph {
    actual val synthOrchestrator: SynthOrchestrator
    actual val synthEngine: SynthEngine
    actual val globalTempo: GlobalTempo

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): DjAppGraph
    }
}
