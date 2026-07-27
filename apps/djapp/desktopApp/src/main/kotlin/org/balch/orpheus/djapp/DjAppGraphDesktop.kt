package org.balch.orpheus.djapp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import org.balch.orpheus.djapp.di.DjAppGraph

/**
 * JVM desktop concrete graph. Declared HERE (not in `:apps:djapp:shared`) so Metro generates the
 * graph in a module that sees `:apps:djapp:ai` when built with `-Pedition=ai` — only then are
 * `AiTabContribution` (AppScope set) and `DjAiViewModel` (FeatureScope map) on the compile
 * classpath and collected. A `core` build simply doesn't have the `ai` module on the classpath, so
 * the tab set is empty and `DjAiViewModel` is absent — correct for the Core edition.
 *
 * Common members (including the eager Pulsar roots) are inherited from [DjAppGraph]; only the
 * factory is declared here.
 */
@DependencyGraph(AppScope::class)
interface DjAppGraphDesktop : DjAppGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): DjAppGraphDesktop
    }
}
