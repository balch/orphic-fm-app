package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import kotlin.experimental.ExperimentalNativeApi

/** iOS [VibeCatalogPolicy]: WIP vibes visible in debug framework binaries only. */
@ContributesTo(AppScope::class)
interface VibeCatalogPolicyProvider {
    companion object {
        @OptIn(ExperimentalNativeApi::class)
        @Provides
        fun provideVibeCatalogPolicy(): VibeCatalogPolicy =
            VibeCatalogPolicy(
                catalogLevel = if (kotlin.native.Platform.isDebugBinary) VibeStatus.WIP else VibeStatus.LIVE,
            )
    }
}
