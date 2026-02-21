package org.balch.orpheus.core.tidal

import org.balch.orpheus.core.audio.SynthEngine

/**
 * Definition of a drum patch for the Orpheus synth engine.
 * Encapsulates the parameters needed to synthesize a specific drum sound.
 */
data class DrumPatch(
    val frequency: Float,
    val envelopeSpeed: Float, // 0.0 = Fast/Percussive, 1.0 = Slow
    val sharpness: Float,     // 0.0 = Sine/Tri, 1.0 = Square/Pulse
    val fmDepth: Float = 0.0f,
    val drive: Float = 0.0f,
    val pan: Float? = null, // Optional static pan
    val drumType: Int? = null, // 0=BD808, 1=SD808, 2=HH808
    val p4: Float = 0.5f,     // extra param (snappy/attackFM)
    val p5: Float = 0.5f      // extra param (selfFM)
)

/**
 * Library of drum synthesis definitions.
 * Maps standard Tidal/General MIDI drum names to synth parameters.
 */
object DrumDefs {
    
    val LIBRARY = mapOf(
        // === KICKS (all use BD 808 engine, type=0) ===
        // Params: frequency=pitch(norm), envelopeSpeed=decay, sharpness=tone, p4=attackFM, p5=selfFM
        "bd" to DrumPatch(
            frequency = 0.19f,    // ~55Hz, standard kick
            envelopeSpeed = 0.35f, // Punchy decay
            sharpness = 0.4f,     // Warm tone
            drumType = 0,
            p4 = 0.3f,            // Moderate attack FM
            p5 = 0.2f             // Low self FM
        ),
        "kick" to DrumPatch(
            frequency = 0.17f,    // Slightly lower
            envelopeSpeed = 0.4f, // Rounder decay
            sharpness = 0.5f,
            drumType = 0,
            p4 = 0.4f,
            p5 = 0.3f
        ),
        "hardkick" to DrumPatch(
            frequency = 0.22f,    // Tighter
            envelopeSpeed = 0.25f, // Snappier
            sharpness = 0.7f,     // More bite
            drumType = 0,
            p4 = 0.7f,            // Heavy attack FM
            p5 = 0.5f
        ),
        "bd808" to DrumPatch(
            frequency = 0.19f,
            envelopeSpeed = 0.5f,
            sharpness = 0.5f,
            drumType = 0,
            p4 = 0.5f,
            p5 = 0.5f
        ),

        // === SNARES (all use SD 808 engine, type=1) ===
        // Params: frequency=pitch(norm), envelopeSpeed=decay, sharpness=tone, p4=snappy
        "sn" to DrumPatch(
            frequency = 0.2f,     // Standard snare pitch
            envelopeSpeed = 0.35f, // Crisp decay
            sharpness = 0.5f,     // Balanced tone
            drumType = 1,
            p4 = 0.6f             // Snappy
        ),
        "sd" to DrumPatch(
            frequency = 0.18f,    // Slightly deeper
            envelopeSpeed = 0.4f, // Fuller body
            sharpness = 0.45f,
            drumType = 1,
            p4 = 0.5f
        ),
        "rim" to DrumPatch(
            frequency = 0.4f,     // Higher pitch for rimshot
            envelopeSpeed = 0.15f, // Very tight
            sharpness = 0.7f,     // Bright
            drumType = 1,
            p4 = 0.2f             // Less snappy, more tonal
        ),
        "cp" to DrumPatch(
            frequency = 0.3f,     // Clap pitch
            envelopeSpeed = 0.3f,
            sharpness = 0.6f,
            drumType = 1,
            p4 = 0.8f             // Very snappy for clap texture
        ),
        "clap" to DrumPatch(
            frequency = 0.3f,
            envelopeSpeed = 0.3f,
            sharpness = 0.6f,
            drumType = 1,
            p4 = 0.8f
        ),
        "sn808" to DrumPatch(
            frequency = 0.2f,
            envelopeSpeed = 0.5f,
            sharpness = 0.5f,
            drumType = 1,
            p4 = 0.5f
        ),

        // === HATS (all use HH 808 engine, type=2) ===
        // Params: frequency=pitch(norm), envelopeSpeed=decay, sharpness=tone, p4=noisiness
        "hh" to DrumPatch(
            frequency = 0.14f,    // Closed hat pitch
            envelopeSpeed = 0.2f, // Tight
            sharpness = 0.5f,
            drumType = 2,
            p4 = 0.3f             // Moderate noise
        ),
        "hat" to DrumPatch(
            frequency = 0.2f,     // Brighter hat
            envelopeSpeed = 0.15f,
            sharpness = 0.6f,
            drumType = 2,
            p4 = 0.25f
        ),
        "ch" to DrumPatch(
            frequency = 0.3f,     // Bright metallic
            envelopeSpeed = 0.1f, // Very tight
            sharpness = 0.7f,
            drumType = 2,
            p4 = 0.15f            // Low noise, more tonal
        ),
        "oh" to DrumPatch(
            frequency = 0.14f,    // Same pitch as closed
            envelopeSpeed = 0.6f, // Long decay for open hat
            sharpness = 0.5f,
            drumType = 2,
            p4 = 0.4f             // More noise
        ),
        "cb" to DrumPatch(
            frequency = 0.5f,     // High pitch cowbell
            envelopeSpeed = 0.25f,
            sharpness = 0.8f,     // Bright tone
            drumType = 2,
            p4 = 0.05f            // Very low noise, tonal
        ),
        "cowbell" to DrumPatch(
            frequency = 0.5f,
            envelopeSpeed = 0.25f,
            sharpness = 0.8f,
            drumType = 2,
            p4 = 0.05f
        ),
        "hh808" to DrumPatch(
            frequency = 0.14f,
            envelopeSpeed = 0.5f,
            sharpness = 0.5f,
            drumType = 2,
            p4 = 0.2f
        ),

        // === TOMS (use BD engine with higher pitch) ===
        "lt" to DrumPatch(
            frequency = 0.35f,    // Low tom
            envelopeSpeed = 0.45f, // Resonant
            sharpness = 0.4f,
            drumType = 0,
            p4 = 0.2f,
            p5 = 0.1f
        ),
        "mt" to DrumPatch(
            frequency = 0.5f,     // Mid tom
            envelopeSpeed = 0.4f,
            sharpness = 0.4f,
            drumType = 0,
            p4 = 0.2f,
            p5 = 0.1f
        ),
        "ht" to DrumPatch(
            frequency = 0.65f,    // High tom
            envelopeSpeed = 0.35f,
            sharpness = 0.45f,
            drumType = 0,
            p4 = 0.25f,
            p5 = 0.15f
        )
    )
    
    /**
     * Trigger a drum sound using the specialized Plaits drum engines.
     * All drums route through the 808 engines (BD=0, SD=1, HH=2).
     */
    fun apply(synthEngine: SynthEngine, voiceIndex: Int, patch: DrumPatch) {
        val type = patch.drumType ?: return
        synthEngine.triggerDrum(
            type,
            1.0f,               // accent
            patch.frequency,    // normalized pitch
            patch.sharpness,    // tone
            patch.envelopeSpeed, // decay
            patch.p4,
            patch.p5
        )
    }
}
