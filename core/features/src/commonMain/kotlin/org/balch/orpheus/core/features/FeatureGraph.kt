package org.balch.orpheus.core.features

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provider
import org.balch.orpheus.core.di.FeatureScope

/**
 * Child graph extension for features.
 *
 * Metro auto-assembles the provider map from all `@ContributesIntoMap(FeatureScope::class)`
 * contributions. The factory is contributed to `AppScope` so `OrpheusGraph` auto-implements it.
 *
 * Features and AI tools live in [FeatureScope]; they can still inject `AppScope` bindings
 * (like `SynthController`, `SynthEngine`) because graph extensions inherit parent bindings.
 */
@GraphExtension(FeatureScope::class)
interface FeatureGraph {
    val featureCollection: FeatureCollection
    val featureCoroutineScope: FeatureCoroutineScope

    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    interface Factory {
        fun create(): FeatureGraph
    }
}
