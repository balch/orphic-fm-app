package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import org.balch.orpheus.core.features.FeatureGraphHolder
import org.balch.orpheus.core.playback.MetadataProducer

/**
 * iOS concrete graph. Generated in `:apps:djapp:shared` (iosMain) — the iOS app links the
 * `DjAppShared` framework and has no separate Gradle entry module, so this is inherently the
 * Core edition (shared never depends on `:apps:djapp:ai`). Common members (including the eager
 * Pulsar roots) are inherited from [DjAppGraph]; only the widget-touched roots and the factory
 * are declared here — mirrors `DjAppGraphAndroid`.
 */
@DependencyGraph(AppScope::class)
interface DjAppGraphIos : DjAppGraph {

    /** Resolves `PulsarFeature`/`TimerFeature` for the widget updater, outside any composition. */
    val featureGraphHolder: FeatureGraphHolder

    /** Now-playing artwork/title source — read by the home-screen widget. */
    val metadataProducer: MetadataProducer

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): DjAppGraphIos
    }
}
