package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.triggers.DrumTriggerSource

/**
 * Owns signal routing decisions: drum/warps/resonator source routing and bypass toggles.
 * Extracted from DspSynthEngine for composition — not DI-injected.
 */
class SynthEngineRouting(
    private val audioEngine: AudioEngine,
    private val nativeBridge: NativeDspBridge,
    private val pluginProvider: DspPluginProvider
) {
    // State
    var fluxClockSource = 0  // 0=Internal, 1=LFO
        private set
    var drumsBypass = true
        private set
    val drumTriggerSources = IntArray(3) { 0 }
    val drumPitchSources = IntArray(3) { 0 }

    // Warps source routing
    private var _warpsCarrierSource = 0
    private var _warpsModulatorSource = 1

    // Resonator mix state
    var resoTargetMix = 0.0f  // Must match ResonatorPlugin default (0=drum)
        private set
    var resoMix = 0.0f
        private set

    // Drums bypass (side effects: rewires drum routing path)
    fun setDrumsBypass(bypass: Boolean) {
        drumsBypass = bypass
        pluginProvider.drumPlugin.setBypass(bypass)
        val chainGain = if (bypass) 0.0f else 1.0f
        val directGain = if (bypass) 1.0f else 0.0f
        // Forward to C++ ODWG graph
        pluginProvider.drumPlugin.setRouting(chainGain, directGain)
    }

    // Drum Sources (side effects: rewires audio inputs + forwards to C++)
    fun setDrumTriggerSource(drumIndex: Int, sourceIndex: Int) {
        if (drumIndex !in 0..2) return
        drumTriggerSources[drumIndex] = sourceIndex

        // Forward to C++ native bridge
        pluginProvider.fluxPlugin.setDrumTriggerSource(drumIndex, sourceIndex)
        // Activate drum voice in C++ so the gate routing code runs.
        // Drum voices start inactive (active=0) and only get activated by pad hits.
        // Without this, the active check returns before external gates are read.
        if (sourceIndex != 0) {
            val voiceIndex = DRUM_VOICE_START + drumIndex
            nativeBridge.nativeSetVoiceActive(voiceIndex, true)
        }
    }

    fun setDrumPitchSource(drumIndex: Int, sourceIndex: Int) {
        if (drumIndex !in 0..2) return
        drumPitchSources[drumIndex] = sourceIndex

        // Map DrumTriggerSource pitch ordinals to X output index (1=X1, 2=X2, 3=X3)
        val xIndex = sourceIndex - DrumTriggerSource.FLUX_X1.ordinal + 1

        // Forward mapped index to C++ native bridge
        pluginProvider.fluxPlugin.setDrumPitchSource(drumIndex, xIndex)
    }

    fun setFluxClockSource(sourceIndex: Int) {
        fluxClockSource = sourceIndex
        // Forward to C++ native bridge
        pluginProvider.fluxPlugin.setClockSource(sourceIndex)
    }

    // Warps source routing (side effects: rewires audio graph)
    fun setWarpsCarrierSource(source: Int) {
        _warpsCarrierSource = source
        audioEngine.setPort(WarpsSymbol.CARRIER_SOURCE.uri, "carrier_source", source.toFloat())
    }

    fun setWarpsModulatorSource(source: Int) {
        _warpsModulatorSource = source
        audioEngine.setPort(WarpsSymbol.MODULATOR_SOURCE.uri, "modulator_source", source.toFloat())
    }

    // Resonator mix routing
    fun setResonatorTargetMix(targetMix: Float) {
        resoTargetMix = targetMix
        pluginProvider.resonatorPlugin.let { reso ->
            audioEngine.setPort(ResonatorSymbol.TARGET_MIX.uri, ResonatorSymbol.TARGET_MIX.symbol, targetMix)
            reso.setPortValue(ResonatorSymbol.TARGET_MIX.symbol, PortValue.FloatValue(targetMix))
        }
        // Forward excitation/bypass gains to C++ graph port map
        val drumExcite = if (targetMix <= 0.5f) 1f else (1f - (targetMix - 0.5f) * 2f).coerceIn(0f, 1f)
        val synthExcite = if (targetMix >= 0.5f) 1f else (targetMix * 2f).coerceIn(0f, 1f)
        pluginProvider.resonatorPlugin.setTargetMixGains(drumExcite, synthExcite)
        updateDirectResonatorGains()
    }

    fun setResonatorMix(value: Float) {
        resoMix = value
        pluginProvider.resonatorPlugin.let { reso ->
            audioEngine.setPort(ResonatorSymbol.MIX.uri, ResonatorSymbol.MIX.symbol, value)
            reso.setPortValue(ResonatorSymbol.MIX.symbol, PortValue.FloatValue(value))
        }
        // Forward synth resonator wet/dry gains to C++ graph port map
        val wet = value.coerceIn(0f, 1f)
        val dry = 1f - wet
        pluginProvider.resonatorPlugin.setMixGains(wet, dry)
        updateDirectResonatorGains()
    }

    fun updateDirectResonatorGains() {
        val drumExcite = if (resoTargetMix <= 0.5f) 1.0f else (1.0f - (resoTargetMix - 0.5f) * 2.0f).coerceIn(0.0f, 1.0f)
        val mixWet = resoMix.coerceIn(0.0f, 1.0f)
        val mixDry = 1.0f - mixWet

        val finalWet = (mixWet * drumExcite)
        val finalDry = ((mixDry * drumExcite) + (1.0f - drumExcite))

        // Forward to C++ engine
        pluginProvider.drumPlugin.setDirectResonatorGains(finalWet, finalDry)
    }
}
