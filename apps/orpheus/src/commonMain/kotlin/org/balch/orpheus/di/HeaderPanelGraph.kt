package org.balch.orpheus.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.di.HeaderPanelScope
import org.balch.orpheus.core.features.FeaturePanel

/**
 * Orpheus-only child graph that materializes `Set<FeaturePanel>` for the
 * collapsible-header UI. Feature modules contribute panels via
 * `@ContributesIntoSet(HeaderPanelScope::class, ...)`; apps that don't render
 * the header (djapp) don't depend on this file, so `HeaderPanelScope` has no
 * graph in djapp and no synthetic Set is emitted there.
 */
@GraphExtension(HeaderPanelScope::class)
interface HeaderPanelGraph {
    val featurePanels: Set<FeaturePanel>

    @ContributesTo(FeatureScope::class)
    @GraphExtension.Factory
    interface Factory {
        fun create(): HeaderPanelGraph
    }
}
