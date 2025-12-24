package org.balch.songe.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.balch.songe.core.audio.dsp.AudioEngine
import org.balch.songe.core.audio.dsp.DspSongeEngine

/**
 * WASM implementation of SongeEngine.
 * Delegates to DspSongeEngine using the Web Audio API-based AudioEngine.
 */
@Inject
@ContributesBinding(AppScope::class)
class WasmSongeEngine : SongeEngine by DspSongeEngine(audioEngine) {
    companion object {
        private val audioEngine = AudioEngine()
    }
}
