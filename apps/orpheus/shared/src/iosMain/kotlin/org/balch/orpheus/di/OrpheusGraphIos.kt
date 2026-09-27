package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import org.balch.orpheus.core.mediapipe.HandTracker

/**
 * iOS concrete graph. Declared in iosMain so Metro can see the iosMain contributions
 * (`IosRepositoryModule`, `IosAudioEngine`, `IosTtsGenerator`, the iOS `VibeCatalogPolicyProvider`).
 * Common members, including the eager Pulsar roots, are inherited from [OrpheusGraph]; only the
 * factory is declared here.
 */
@DependencyGraph(AppScope::class)
interface OrpheusGraphIos : OrpheusGraph {

    /** The `@SingleIn` tracker the Compose UI observes; Swift's camera bridge pushes into it. */
    val handTracker: HandTracker

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): OrpheusGraphIos
    }
}
