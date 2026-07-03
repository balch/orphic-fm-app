package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/** WASM [VibeCatalogPolicy]: the public site never shows works-in-progress. */
@ContributesTo(AppScope::class)
interface VibeCatalogPolicyProvider {
    companion object {
        @Provides
        fun provideVibeCatalogPolicy(): VibeCatalogPolicy = VibeCatalogPolicy(catalogLevel = VibeStatus.LIVE)
    }
}
