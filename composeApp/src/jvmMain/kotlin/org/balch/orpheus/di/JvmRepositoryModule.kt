package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.JvmAppPreferencesRepository
import org.balch.orpheus.core.presets.DronePresetRepository
import org.balch.orpheus.core.presets.JvmDronePresetRepository

/**
 * JVM-specific module providing repository implementations.
 */
@ContributesTo(AppScope::class)
interface JvmRepositoryModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideDronePresetRepository(impl: JvmDronePresetRepository): DronePresetRepository = impl

        @Provides
        @SingleIn(AppScope::class)
        fun provideAppPreferencesRepository(impl: JvmAppPreferencesRepository): AppPreferencesRepository = impl
    }
}
