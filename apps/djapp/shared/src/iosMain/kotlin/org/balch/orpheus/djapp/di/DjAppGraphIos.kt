package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph

/**
 * iOS concrete graph for the Core edition. Generated in `:apps:djapp:shared` (iosMain), which never
 * depends on `:apps:djapp:ai`; the AI edition's graph lives there instead (`DjAppGraphIosAi`).
 * Common members (including the eager Pulsar roots) are inherited from [DjAppGraph], the
 * widget-touched roots from [IosDjGraph]; only the factory is declared here.
 */
@DependencyGraph(AppScope::class)
interface DjAppGraphIos : IosDjGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): DjAppGraphIos
    }
}
