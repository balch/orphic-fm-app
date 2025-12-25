package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.coroutines.DefaultDispatcherProvider
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiController
import org.balch.orpheus.core.midi.MidiMappingRepository
import org.balch.orpheus.core.midi.createMidiAccess
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.DronePresetRepository

/**
 * Module providing core dependencies for the Orpheus application. Used by Metro DI to provide
 * singleton instances of repositories and infrastructure.
 */
@ContributesTo(AppScope::class)
interface OrpheusModule {
    companion object Companion {
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
