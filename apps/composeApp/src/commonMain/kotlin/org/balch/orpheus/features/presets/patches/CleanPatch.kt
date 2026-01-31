package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.features.lfo.HyperLfoMode

/**
 * Clean Patch - Good for REPL
 *
 * */
@Inject
@ContributesIntoSet(AppScope::class)
class CleanPatch : SynthPatch {
    override val id = "clean"
    override val name = "Clean"
    override val preset = DronePreset(
        name = "Clean",
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
        voiceModDepths = List(4) { 0f },
        voiceEnvelopeSpeeds = List(4) { 0f },
        pairSharpness = List(4) { 0f },
        duoModSources = List(4) { ModSource.OFF },
        hyperLfoA = 0.0f,
        hyperLfoB = 0.0f,
        hyperLfoMode = HyperLfoMode.OFF,
        hyperLfoLink = false,
        delayTime1 = 0f,
        delayTime2 = 0f,
        delayMod1 = 0f,
        delayMod2 = 0f,
        delayFeedback = 0f,
        delayMix = 0f,
        delayModSourceIsLfo = true,
        delayLfoWaveformIsTriangle = true,
        // Overall mix
        drive = 0f,       // min distortion by default
        distortionMix = 0.5f,
        fmStructureCrossQuad = false,  // Standard FM routing
        totalFeedback = 0.0f,
        createdAt = 0L
    )
}
