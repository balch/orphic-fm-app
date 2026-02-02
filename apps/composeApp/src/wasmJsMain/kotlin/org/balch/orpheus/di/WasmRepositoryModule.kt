package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.WasmAppPreferencesRepository
import org.balch.orpheus.core.presets.SynthPresetRepository
import org.balch.orpheus.core.presets.WasmSynthPresetRepository

/**
 * WASM-specific module providing repository implementations.
 */
@ContributesTo(AppScope::class)
interface WasmRepositoryModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideSynthPresetRepository(impl: WasmSynthPresetRepository): SynthPresetRepository = impl

        @Provides
        @SingleIn(AppScope::class)
        fun provideAppPreferencesRepository(impl: WasmAppPreferencesRepository): AppPreferencesRepository = impl
    }
}
