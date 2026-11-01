package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.StartupRoot
import org.balch.orpheus.core.features.FeatureGraphHolder
import org.balch.orpheus.features.cowbell.CowbellFeature

/**
 * Builds the cowbell at launch, so its saved knobs and engine restore before the tab opens and a
 * MIDI or AI strike sounds as the user left it. Both editions show the Cowbell tab.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<@StartupRoot Any>())
class CowbellStartup(holder: FeatureGraphHolder) {
    init {
        holder.featureGraph.featureCollection.getFeature(CowbellFeature::class)
    }
}
