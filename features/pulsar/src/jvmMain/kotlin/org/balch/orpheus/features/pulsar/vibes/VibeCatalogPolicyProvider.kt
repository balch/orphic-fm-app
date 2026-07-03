package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * Desktop JVM [VibeCatalogPolicy]: the visible tier is driven by `-Pcatalog=live|wip|shelf`
 * (default `live`), surfaced by the desktopApp build file as the `-Dcatalog` system property.
 * The desktop app is the ear-test harness, so raise the tier to audition WIP or shelved vibes.
 * An unset or unrecognized value falls back to LIVE (see [VibeCatalog.vibeStatusFromArg]).
 */
@ContributesTo(AppScope::class)
interface VibeCatalogPolicyProvider {
    companion object {
        @Provides
        fun provideVibeCatalogPolicy(): VibeCatalogPolicy =
            VibeCatalogPolicy(catalogLevel = VibeCatalog.vibeStatusFromArg(System.getProperty("catalog")))
    }
}
