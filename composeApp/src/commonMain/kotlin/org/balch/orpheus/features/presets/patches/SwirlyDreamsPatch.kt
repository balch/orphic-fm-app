package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.features.lfo.HyperLfoMode

/**
 * Swirly Dreams - Psychedelic FM modulation with LFO-driven delay.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SwirlyDreamsPatch : SynthPatch {
    override val id = "swirly_dreams"
    override val name = "Swirly Dreams"
    override val preset = DronePreset(
        name = "Swirly Dreams",
        voiceTunes = listOf(0.20f, 0.25f, 0.30f, 0.35f, 0.40f, 0.45f, 0.50f, 0.55f),
        voiceModDepths = listOf(0.6f, 0.5f, 0.6f, 0.5f, 0.6f, 0.5f, 0.6f, 0.5f),
        voiceEnvelopeSpeeds = listOf(0.4f, 0.4f, 0.5f, 0.5f, 0.6f, 0.6f, 0.7f, 0.7f),
        pairSharpness = listOf(0.4f, 0.5f, 0.4f, 0.5f),
        duoModSources = List(4) { ModSource.LFO},
        hyperLfoA = 0.15f,
        hyperLfoB = 0.12f,
        hyperLfoMode = HyperLfoMode.AND,
        hyperLfoLink = true,
        delayTime1 = 0.45f,
        delayTime2 = 0.65f,
        delayMod1 = 0.5f,
        delayMod2 = 0.6f,
        delayFeedback = 0.65f,
        delayMix = 0.5f,
        delayModSourceIsLfo = true,
        delayLfoWaveformIsTriangle = true,
        drive = 0.2f,
        distortionMix = 0.25f,
        fmStructureCrossQuad = false,
        totalFeedback = 0.35f,
        createdAt = 0L
    )
}
