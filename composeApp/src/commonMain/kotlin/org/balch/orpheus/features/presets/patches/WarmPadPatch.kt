package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.SynthPatch

/**
 * Warm Pad - Gentle, warm pad with subtle modulation.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class WarmPadPatch : SynthPatch {
    override val id = "warm_pad"
    override val name = "Warm Pad"
    override val preset = DronePreset(
        name = "Warm Pad",
        voiceTunes = listOf(0.25f, 0.32f, 0.39f, 0.46f, 0.25f, 0.32f, 0.39f, 0.46f),
        voiceModDepths = listOf(0.2f, 0.0f, 0.15f, 0.0f, 0.2f, 0.0f, 0.15f, 0.0f),
        voiceEnvelopeSpeeds = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0.6f, 0.6f),
        pairSharpness = listOf(0.3f, 0.3f, 0.3f, 0.3f),
        duoModSources = listOf("LFO", "LFO", "LFO", "LFO"),
        hyperLfoA = 0.25f,
        hyperLfoB = 0.18f,
        hyperLfoMode = "FREQ",
        hyperLfoLink = true,
        delayTime1 = 0.4f,
        delayTime2 = 0.5f,
        delayMod1 = 0.2f,
        delayMod2 = 0.25f,
        delayFeedback = 0.6f,
        delayMix = 0.45f,
        delayModSourceIsLfo = true,
        delayLfoWaveformIsTriangle = true,
        masterVolume = 0.65f,
        drive = 0.1f,
        distortionMix = 0.4f,
        fmStructureCrossQuad = false,
        totalFeedback = 0.15f,
        createdAt = 0L
    )
}
