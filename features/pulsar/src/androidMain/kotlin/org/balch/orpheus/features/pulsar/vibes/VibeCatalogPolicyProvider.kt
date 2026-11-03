package org.balch.orpheus.features.pulsar.vibes

import android.app.Application
import android.content.pm.ApplicationInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * Android [VibeCatalogPolicy]: WIP vibes are visible on debuggable builds and on `aiDebugRelease`.
 *
 * FLAG_DEBUGGABLE mirrors the app module's `BuildConfig.DEBUG` semantics without this library
 * needing a per-buildType compilation (AGP KMP libraries are single-variant): the `debug` build
 * type is debuggable; `release` and `debugRelease` (initWith(release), R8) are not. For the same
 * reason `aiDebugRelease` is told apart by its id suffix, the flavor's `.ai` plus the type's `.debug`.
 */
@ContributesTo(AppScope::class)
@BindingContainer
interface VibeCatalogPolicyProvider {
    companion object {
        @Provides
        fun provideVibeCatalogPolicy(application: Application): VibeCatalogPolicy =
            VibeCatalogPolicy(
                catalogLevel = if (
                    application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
                    application.packageName.endsWith(".ai.debug")
                ) {
                    VibeStatus.WIP
                } else {
                    VibeStatus.LIVE
                },
            )
    }
}
