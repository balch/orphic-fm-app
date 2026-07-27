package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.mediapipe.HandTracker
import org.balch.orpheus.core.mediapipe.WasmHandTracker
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.WasmAppPreferencesRepository
import org.balch.orpheus.core.presets.SynthPresetRepository
import org.balch.orpheus.core.presets.WasmSynthPresetRepository

/**
 * WASM-specific bindings. See [AndroidRepositoryModule] for why these live in a module instead of
 * `@ContributesBinding` on the impls, and for the `@Binds` scoping note.
 */
@ContributesTo(AppScope::class)
interface WasmRepositoryModule {
    @Binds val WasmSynthPresetRepository.bindSynthPresetRepository: SynthPresetRepository

    @Binds val WasmAppPreferencesRepository.bindAppPreferencesRepository: AppPreferencesRepository

    companion object {
        /** Not `@Binds`: [WasmHandTracker] is not `@Inject`, it is constructed here. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideHandTracker(): HandTracker = WasmHandTracker()
    }
}
