package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph

/**
 * JVM desktop concrete graph. Declared in jvmMain so Metro can see the jvmMain contributions
 * (`JvmRepositoryModule`, `AudioEngineProvider`, `JvmTtsGenerator`, `DesktopHandTracker`, the JVM
 * `VibeCatalogPolicyProvider`). Common members, including the eager Pulsar roots, are inherited
 * from [OrpheusGraph]; only the factory is declared here.
 */
@DependencyGraph(AppScope::class)
interface OrpheusGraphJvm : OrpheusGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): OrpheusGraphJvm
    }
}
