package org.balch.orpheus.core.audio.dsp.synth.warps

/**
 * Audio sources available for Warps carrier and modulator inputs.
 */
enum class WarpsSource(val displayName: String) {
    SYNTH("Synth"),
    DRUMS("Drums"),
    ALL("ALL"),
    LFO("LFO")
}

enum class ModulationAlgorithm {
    XFADE,
    FOLD,
    ANALOG_RINGMOD,
    DIGITAL_RINGMOD,
    XOR,
    COMPARE,
    SPECTRAL,
    MORPH,
    VOCODER
}

class WarpsParameters {
    var channelDrive = floatArrayOf(0f, 0f)
    var modulationAlgorithm: Float = 0f
    var modulationParameter: Float = 0f
    
    // Apply a non-linear response to the parameter of all algorithms between
    // 1 and 4.
    fun skewedModulationParameter(): Float {
        var skew = 0f
        if (modulationAlgorithm <= 0.125f) {
            skew = modulationAlgorithm * 8.0f
        } else if (modulationAlgorithm >= 0.625f) {
            skew = 1.0f
        } else if (modulationAlgorithm >= 0.5f) {
            skew = (0.625f - modulationAlgorithm) * 8.0f
        }
        return modulationParameter * (1.0f + skew * (modulationParameter - 1.0f))
    }
}
