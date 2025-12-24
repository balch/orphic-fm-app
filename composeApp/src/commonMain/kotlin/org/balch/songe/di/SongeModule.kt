package org.balch.songe.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.songe.core.coroutines.DefaultDispatcherProvider
import org.balch.songe.core.coroutines.DispatcherProvider
import org.balch.songe.core.midi.MidiController
import org.balch.songe.core.midi.MidiMappingRepository
import org.balch.songe.core.midi.createMidiAccess
import org.balch.songe.core.preferences.AppPreferencesRepository
import org.balch.songe.core.presets.DronePresetRepository

/**
 * Module providing core dependencies for the Songe application. Used by Metro DI to provide
 * singleton instances of repositories and infrastructure.
 */
@ContributesTo(AppScope::class)
interface SongeModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

        @Provides
        @SingleIn(AppScope::class)
        fun provideMidiController(): MidiController = MidiController { createMidiAccess() }

        @Provides
        @SingleIn(AppScope::class)
        fun provideMidiMappingRepository(): MidiMappingRepository = MidiMappingRepository()

        @Provides
        @SingleIn(AppScope::class)
        fun provideDronePresetRepository(): DronePresetRepository = DronePresetRepository()

        @Provides
        @SingleIn(AppScope::class)
        fun provideAppPreferencesRepository(): AppPreferencesRepository = AppPreferencesRepository()
    }
}
