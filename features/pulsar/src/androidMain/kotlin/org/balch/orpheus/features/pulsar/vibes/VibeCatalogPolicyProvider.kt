package org.balch.orpheus.features.pulsar.vibes

import android.app.Application
import android.content.pm.ApplicationInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * Android [VibeCatalogPolicy]: WIP vibes are visible only on debuggable builds.
 *
 * FLAG_DEBUGGABLE mirrors the app module's `BuildConfig.DEBUG` semantics without this library
 * needing a per-buildType compilation (AGP KMP libraries are single-variant): the `debug` build
 * type is debuggable; `release` and `debugRelease` (initWith(release), R8) are not.
 */
@ContributesTo(AppScope::class)
interface VibeCatalogPolicyProvider {
    companion object {
        @Provides
        fun provideVibeCatalogPolicy(application: Application): VibeCatalogPolicy =
            VibeCatalogPolicy(
                catalogLevel = if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    VibeStatus.WIP
                } else {
                    VibeStatus.LIVE
                },
            )
    }
}
