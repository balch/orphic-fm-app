package org.balch.orpheus.di

import android.app.Application
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.preferences.AndroidAppPreferencesRepository
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.AndroidDronePresetRepository
import org.balch.orpheus.core.presets.DronePresetRepository

/**
 * Android-specific module providing repository implementations.
 * These need to be in a module (not just @ContributesBinding) because
 * KMP Metro doesn't see androidMain bindings during commonMain compilation.
 */
@ContributesTo(AppScope::class)
interface AndroidRepositoryModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideDronePresetRepository(impl: AndroidDronePresetRepository): DronePresetRepository = impl

        @Provides
        @SingleIn(AppScope::class)
        fun provideAppPreferencesRepository(impl: AndroidAppPreferencesRepository): AppPreferencesRepository = impl
        
        /**
         * Provide Context from Application for components that need it.
         * Application is passed to the factory, and extends Context.
         */
        @Provides
        fun provideContext(application: Application): Context = application
    }
}
