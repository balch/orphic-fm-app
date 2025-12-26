package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.features.lfo.HyperLfoMode

/**
 * Default Patch - A neutral starting point with all parameters at sensible defaults.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DefaultPatch : SynthPatch {
    override val id = "default"
    override val name = "Default"
    override val preset = DronePreset(
        name = "Default",
        voiceTunes = listOf(0.2f, 0.27f, 0.34f, 0.4f, 0.47f, 0.54f, 0.61f, 0.68f),
        voiceModDepths = List(8) { 0.0f },
        voiceEnvelopeSpeeds = List(8) { 0.0f },
        pairSharpness = List(4) { 0.0f },
        duoModSources = List(4) { ModSource.OFF },
        hyperLfoA = 0.0f,
        hyperLfoB = 0.0f,
        hyperLfoMode = HyperLfoMode.OFF,
        hyperLfoLink = false,
        delayTime1 = 0.0f,
        delayTime2 = 0.0f,
        delayMod1 = 0.0f,
        delayMod2 = 0.0f,
        delayFeedback = 0.5f,
        delayMix = 0.5f,
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
