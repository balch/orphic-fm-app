package org.balch.orpheus.plugins.plaits

/**
 * Universal parameter set passed to every [PlaitsEngine.render] call.
 * Maps directly to Plaits' EngineParameters struct.
 *
 * Mutable to allow reuse without heap allocation in the audio thread.
 */
class EngineParameters(
    var trigger: TriggerState = TriggerState.LOW,
    var note: Float = 60f,       // MIDI note number
    var timbre: Float = 0.5f,    // 0..1 (maps to "tone" for drums)
    var morph: Float = 0.5f,     // 0..1 (maps to "decay" for drums)
    var harmonics: Float = 0.5f, // 0..1 (maps to "p4" for drums)
    var accent: Float = 0.8f     // 0..1
) {
    fun set(
        trigger: TriggerState,
        note: Float,
        timbre: Float,
        morph: Float,
        harmonics: Float,
        accent: Float
    ) {
        this.trigger = trigger
        this.note = note
        this.timbre = timbre
        this.morph = morph
        this.harmonics = harmonics
        this.accent = accent
    }
}

/**
 * Trigger state matching Plaits' TriggerState enum.
 */
enum class TriggerState(val bits: Int) {
    LOW(0),
    RISING_EDGE(1),
    UNPATCHED(2),
    HIGH(4),
    RISING_EDGE_HIGH(5)
}
