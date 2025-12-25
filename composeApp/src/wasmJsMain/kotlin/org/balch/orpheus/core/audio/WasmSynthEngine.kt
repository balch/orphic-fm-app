package org.balch.orpheus.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspSynthEngine

/**
 * WASM implementation of SynthEngine.
 * Delegates to DspSynthEngine using the Web Audio API-based AudioEngine.
 */
@Inject
@ContributesBinding(AppScope::class)
class WasmSynthEngine : SynthEngine by DspSynthEngine(audioEngine) {
    companion object {
        private val audioEngine = AudioEngine()
    }
}
