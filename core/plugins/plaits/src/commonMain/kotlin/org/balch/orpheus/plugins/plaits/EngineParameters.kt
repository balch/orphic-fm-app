package org.balch.orpheus.plugins.plaits

/**
 * Universal parameter set passed to every [PlaitsEngine.render] call.
 * Maps directly to Plaits' EngineParameters struct.
 */
data class EngineParameters(
    val trigger: TriggerState = TriggerState.LOW,
    val note: Float = 60f,       // MIDI note number
    val timbre: Float = 0.5f,    // 0..1 (maps to "tone" for drums)
    val morph: Float = 0.5f,     // 0..1 (maps to "decay" for drums)
    val harmonics: Float = 0.5f, // 0..1 (maps to "p4" for drums)
    val accent: Float = 0.8f     // 0..1
)

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
