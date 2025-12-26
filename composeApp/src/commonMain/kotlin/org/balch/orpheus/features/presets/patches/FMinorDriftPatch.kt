package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.features.lfo.HyperLfoMode

/**
 * F# Minor Drift - Clean, drifting pad with gentle frequency modulation.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class FMinorDriftPatch : SynthPatch {
    override val id = "f_minor_drift"
    override val name = "F# Minor Drift"
    override val preset = DronePreset(
        name = "F# Minor Drift",
        voiceTunes = listOf(0.20f, 0.27f, 0.34f, 0.40f, 0.47f, 0.54f, 0.61f, 0.68f),
        voiceModDepths = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
        voiceEnvelopeSpeeds = listOf(0.3f, 0.3f, 0.3f, 0.3f, 0.4f, 0.4f, 0.4f, 0.4f),
        pairSharpness = listOf(0.0f, 0.0f, 0.0f, 0.0f),
        duoModSources = List(4) { ModSource.OFF },
        hyperLfoA = 0.15f,
        hyperLfoB = 0.12f,
        hyperLfoMode = HyperLfoMode.OR,
        hyperLfoLink = false,
        delayTime1 = 0.35f,
        delayTime2 = 0.45f,
        delayMod1 = 0.1f,
        delayMod2 = 0.15f,
        delayFeedback = 0.55f,
        delayMix = 0.4f,
        delayModSourceIsLfo = true,
        delayLfoWaveformIsTriangle = true,
        masterVolume = 0.7f,
        drive = 0.0f,
        distortionMix = 0.5f,
        fmStructureCrossQuad = false,
        totalFeedback = 0.0f,
        createdAt = 0L
    )
}
