package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.mediapipe.DesktopHandTracker
import org.balch.orpheus.core.mediapipe.HandTracker
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.JvmAppPreferencesRepository
import org.balch.orpheus.core.presets.JvmSynthPresetRepository
import org.balch.orpheus.core.presets.SynthPresetRepository

/**
 * JVM-specific bindings. See [AndroidRepositoryModule] for why these live in a module instead of
 * `@ContributesBinding` on the impls, and for the `@Binds` scoping note.
 */
@ContributesTo(AppScope::class)
interface JvmRepositoryModule {
    @Binds val JvmSynthPresetRepository.bindSynthPresetRepository: SynthPresetRepository

    @Binds val JvmAppPreferencesRepository.bindAppPreferencesRepository: AppPreferencesRepository

    companion object {
        /** Not `@Binds`: [DesktopHandTracker] is not `@Inject`, it is constructed here. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideHandTracker(): HandTracker = DesktopHandTracker()
    }
}
