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
        // === KICKS ===
        "bd" to DrumPatch(
            frequency = 55.0f,
            envelopeSpeed = 0.0f, // Fast attack/decay
            sharpness = 0.1f,     // Mostly sine/tri for clean low end
            drive = 0.2f          // Slight saturation
        ),
        "kick" to DrumPatch(
            frequency = 50.0f,
            envelopeSpeed = 0.0f,
            sharpness = 0.2f,     // A bit more harmonics
            drive = 0.4f
        ),
        "hardkick" to DrumPatch(
            frequency = 60.0f,
            envelopeSpeed = 0.0f,
            sharpness = 0.6f,     // Square-ish for hard techno kick
            drive = 0.8f
        ),
        
        // === SNARES ===
        "sn" to DrumPatch(
            frequency = 220.0f,
            envelopeSpeed = 0.0f,
            sharpness = 0.8f,     // Square/Pulse for noise-like snap
            fmDepth = 0.3f,       // Add inharmonic frequencies
            drive = 0.1f
        ),
        "sd" to DrumPatch(
            frequency = 200.0f,
            envelopeSpeed = 0.05f, // Slightly fuller body
            sharpness = 0.7f,
            fmDepth = 0.2f
        ),
        "rim" to DrumPatch(
            frequency = 400.0f,
            envelopeSpeed = 0.0f,
            sharpness = 0.4f,
            fmDepth = 0.1f
        ),
        
        // === HATS ===
        "hh" to DrumPatch(
            frequency = 900.0f,   // Lowered base pitch for metallic body
            envelopeSpeed = 0.0f, // Very short
            sharpness = 1.0f,     // Pure square/pulse
            fmDepth = 0.8f,       // High FM for metallic noise
            drive = 0.0f
        ),
        "oh" to DrumPatch( // Open Hat
            frequency = 900.0f,
            envelopeSpeed = 0.3f, // Longer decay
            sharpness = 1.0f,
            fmDepth = 0.8f
        ),
        "hat" to DrumPatch(
            frequency = 1200.0f,
            envelopeSpeed = 0.0f,
            sharpness = 0.9f,
            fmDepth = 0.7f
        ),
        
        // === TOMS ===
        "lt" to DrumPatch( // Low Tom
            frequency = 110.0f,
            envelopeSpeed = 0.2f, // Resonant decay
            sharpness = 0.3f,
            fmDepth = 0.1f
        ),
        "mt" to DrumPatch( // Mid Tom
            frequency = 165.0f,
            envelopeSpeed = 0.2f,
            sharpness = 0.3f,
            fmDepth = 0.1f
        ),
        "ht" to DrumPatch( // High Tom
            frequency = 220.0f,
            envelopeSpeed = 0.2f,
            sharpness = 0.3f,
            fmDepth = 0.1f
        ),
        
        // === PERC ===
        "cp" to DrumPatch( // Clap
            frequency = 800.0f,
            envelopeSpeed = 0.1f, // Spread out snap
            sharpness = 0.9f,
            fmDepth = 0.5f,
            drive = 0.3f
        ),
        "cb" to DrumPatch( // Cowbell (808-ish)
            frequency = 540.0f,   // Standard 808 CB pitch area
            envelopeSpeed = 0.1f,
            sharpness = 1.0f,
            fmDepth = 0.0f        // Square wave body
        ),
        "ch" to DrumPatch( // Chime/Metallic
            frequency = 2000.0f,
            envelopeSpeed = 0.4f,
            sharpness = 0.5f,
            fmDepth = 1.0f        // Max FM for bell tones
        ),
        
        // === 808 ENGINES ===
        "bd808" to DrumPatch(
            frequency = 55.0f,
            envelopeSpeed = 0.5f, // Decay
            sharpness = 0.5f,     // Tone
            drumType = 0,
            p4 = 0.5f, // Attack FM
            p5 = 0.5f  // Self FM
        ),
        "sn808" to DrumPatch(
            frequency = 180.0f,
            envelopeSpeed = 0.5f, // Decay
            sharpness = 0.5f,     // Tone
            drumType = 1,
            p4 = 0.5f             // Snappy
        ),
        "hh808" to DrumPatch(
            frequency = 400.0f,
            envelopeSpeed = 0.5f, // Decay
            sharpness = 0.5f,     // Tone
            drumType = 2,
            p4 = 0.2f             // Noisiness
        )
    )
    
    /**
     * Apply a drum patch to a specific voice engine.
     */
    fun apply(synthEngine: SynthEngine, voiceIndex: Int, patch: DrumPatch) {
        if (patch.drumType != null) {
            // Trigger specialized drum engine
            synthEngine.triggerDrum(
                patch.drumType,
                1.0f, // accent
                patch.frequency,
                patch.sharpness, // TONE mapped to sharpness
                patch.envelopeSpeed, // DECAY mapped to envelopeSpeed
                patch.p4,
                patch.p5
            )
            return
        }

        val quadIndex = voiceIndex / 4
        
        // Envelope: Drum sounds are percussive
        synthEngine.setVoiceEnvelopeSpeed(voiceIndex, patch.envelopeSpeed)
        
        // Waveform/Timbre
        // Sharpness is applied per pair in SynthEngine, but we effectively set it for the pair
        // This might conflict if two voices in a pair try to set different sharpnesses.
        // For now, last writer wins.
        synthEngine.setPairSharpness(voiceIndex / 2, patch.sharpness)
        
        // FM/Timbre Modulation
        synthEngine.setVoiceFmDepth(voiceIndex, patch.fmDepth)
        
        // Drive (Global effect, but we can nudge it? Or maybe per voice drive doesn't exist)
        // SynthEngine has global setDrive. We shouldn't change global drive per drum hit
        // as it would affect everything.
        // TODO: Implement per-voice saturation? For now, we ignore drive or apply it very carefully.
        // Actually, if we are soloing/playing mostly drums, we could set it?
        // Risky. Let's skip drive for now to avoid side effects.
    }
}
