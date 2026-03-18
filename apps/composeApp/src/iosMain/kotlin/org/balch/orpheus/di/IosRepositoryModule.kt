package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.mediapipe.HandTracker
import org.balch.orpheus.core.mediapipe.IosHandTracker
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.IosAppPreferencesRepository
import org.balch.orpheus.core.presets.IosSynthPresetRepository
import org.balch.orpheus.core.presets.SynthPresetRepository

/**
 * iOS-specific module providing repository implementations.
 */
@ContributesTo(AppScope::class)
interface IosRepositoryModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideAppPreferencesRepository(impl: IosAppPreferencesRepository): AppPreferencesRepository = impl

        @Provides
        @SingleIn(AppScope::class)
        fun provideHandTracker(): HandTracker = IosHandTracker()

        @Provides
        @SingleIn(AppScope::class)
        fun provideSynthPresetRepository(impl: IosSynthPresetRepository): SynthPresetRepository = impl
    }
}
