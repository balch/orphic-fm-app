package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph

/**
 * iOS concrete graph. Declared in iosMain so Metro can see the iosMain contributions
 * (`IosRepositoryModule`, `IosAudioEngine`, `IosTtsGenerator`, the iOS `VibeCatalogPolicyProvider`).
 * Common members, including the eager Pulsar roots, are inherited from [OrpheusGraph]; only the
 * factory is declared here.
 */
@DependencyGraph(AppScope::class)
interface OrpheusGraphIos : OrpheusGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): OrpheusGraphIos
    }
}
