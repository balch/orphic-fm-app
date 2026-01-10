package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.features.lfo.HyperLfoMode

/**
 * Default Patch - A rich, musical starting point optimized for pleasant exploration.
 * 
 * Tuning philosophy:
 * - Voices form a harmonious minor 7th chord spread across 4 octaves
 * - Slight detuning adds warmth without being dissonant
 * - Gentle delay adds space without overwhelming
 * - FM depth is subtle to add character without harshness
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DefaultPatch : SynthPatch {
    override val id = "default"
    override val name = "Default"
    override val preset = DronePreset(
        name = "Default",
        // Voice tunes create a minor 7th chord across octaves (Am7 voicing)
        // Low octave (pair 0): A1, C2 (root, minor 3rd)
        // Mid-low (pair 1): E2, G2 (5th, 7th)  
        // Mid-high (pair 2): A2, C3 (octave, minor 3rd)
        // High octave (pair 3): E3, G3 (5th, 7th)
        // Values: 0.0=55Hz, 0.25=110Hz, 0.5=220Hz, 0.75=440Hz, 1.0=880Hz
        voiceTunes = listOf(
            0.15f,  // Voice 0: ~82Hz (low A)
            0.20f,  // Voice 1: ~98Hz (low C)
            0.28f,  // Voice 2: ~123Hz (low E)
            0.32f,  // Voice 3: ~147Hz (low G)
            0.40f,  // Voice 4: ~165Hz (A)
            0.45f,  // Voice 5: ~196Hz (C)
            0.53f,  // Voice 6: ~247Hz (E)
            0.58f   // Voice 7: ~294Hz (G)
        ),
        // Slight FM modulation for warmth
        voiceModDepths = listOf(0.05f, 0.05f, 0.08f, 0.08f, 0.10f, 0.10f, 0.12f, 0.12f),
        // Moderate envelope speeds - not too snappy, not too slow
        voiceEnvelopeSpeeds = listOf(0.3f, 0.3f, 0.3f, 0.3f, 0.4f, 0.4f, 0.4f, 0.4f),
        // Slight harmonic content, more on higher voices
        pairSharpness = listOf(0.0f, 0.1f, 0.2f, 0.3f),
        duoModSources = listOf(
            ModSource.LFO, ModSource.VOICE_FM, ModSource.VOICE_FM, ModSource.LFO
        ),
        // Gentle LFO rates for subtle movement
        hyperLfoA = 0.0f,  // Slower, more subtle
        hyperLfoB = 0.1f,  // Slightly faster for variety
        hyperLfoMode = HyperLfoMode.OFF,
        hyperLfoLink = false,
        // Delay creates nice ambient space
        delayTime1 = 0.65f,  // ~650ms - musical timing
        delayTime2 = 0.45f,  // ~450ms - creates nice polyrhythm
        delayMod1 = 0.05f,   // Subtle wobble
        delayMod2 = 0.10f,
        delayFeedback = 0.35f,  // Moderate, won't overwhelm
        delayMix = 0.25f,    // Present but not dominant
        delayModSourceIsLfo = true,
        delayLfoWaveformIsTriangle = true,
        // Overall mix
        drive = 0.1f,       // min distortion by default
        distortionMix = 0.5f,
        fmStructureCrossQuad = false,  // Standard FM routing
        totalFeedback = 0.0f,
        createdAt = 0L
    )
}
