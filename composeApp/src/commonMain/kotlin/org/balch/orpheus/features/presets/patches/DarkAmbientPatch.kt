package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.features.lfo.HyperLfoMode

/**
 * Dark Ambient - Deep, evolving soundscape with FM and delay modulation.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DarkAmbientPatch : SynthPatch {
    override val id = "dark_ambient"
    override val name = "Dark Ambient"
    override val preset = DronePreset(
        name = "Dark Ambient",
        voiceTunes = listOf(0.15f, 0.18f, 0.22f, 0.28f, 0.35f, 0.38f, 0.42f, 0.48f),
        voiceModDepths = listOf(0.35f, 0.0f, 0.4f, 0.0f, 0.3f, 0.0f, 0.35f, 0.0f),
        voiceEnvelopeSpeeds = listOf(0.7f, 0.7f, 0.8f, 0.8f, 0.7f, 0.7f, 0.8f, 0.8f),
        pairSharpness = listOf(0.5f, 0.6f, 0.5f, 0.6f),
        duoModSources = List(4) { ModSource.VOICE_FM },
        hyperLfoA = 0.08f,
        hyperLfoB = 0.05f,
        hyperLfoMode = HyperLfoMode.AND,
        hyperLfoLink = false,
        delayTime1 = 0.55f,
        delayTime2 = 0.75f,
        delayMod1 = 0.3f,
        delayMod2 = 0.4f,
        delayFeedback = 0.7f,
        delayMix = 0.55f,
        delayModSourceIsLfo = true,
        delayLfoWaveformIsTriangle = false,
        masterVolume = 0.6f,
        drive = 0.25f,
        distortionMix = 0.35f,
        fmStructureCrossQuad = true,
        totalFeedback = 0.25f,
        createdAt = 0L
    )
}
