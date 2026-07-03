package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph

/**
 * iOS concrete graph. Generated in `:apps:djapp:shared` (iosMain) — the iOS app links the
 * `DjAppShared` framework and has no separate Gradle entry module, so this is inherently the
 * Core edition (shared never depends on `:apps:djapp:ai`). Common members (including the eager
 * Pulsar roots) are inherited from [DjAppGraph]; only the factory is declared here.
 */
@DependencyGraph(AppScope::class)
interface DjAppGraphIos : DjAppGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): DjAppGraphIos
    }
}
