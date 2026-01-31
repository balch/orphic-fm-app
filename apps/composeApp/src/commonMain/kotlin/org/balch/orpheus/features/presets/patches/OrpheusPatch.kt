package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.SynthPatch

/**
 * Warm Pad - Gentle, warm pad with subtle modulation.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class OrpheusPatch : SynthPatch {
    override val id = "orpheus"
    override val name = "Orpheus"
    override val preset = DronePreset(
        name = "Orpheus",
        voiceTunes = listOf(
            0.60874975f, 0.68999964f, 0.4812498f, 0.4962499f, 0.63999975f, 0.6599998f,
            0.49875003f, 0.55f, 0.75f, 0.82f, 0.89f, 0.96f
        ),
        voiceModDepths = listOf(
            0.59749967f, 0.59749967f, 0.26875004f, 0.26875004f, 0.48874983f, 0.48874983f,
            0.45749986f, 0.45749986f, 0.0f, 0.0f, 0.0f, 0.0f
        ),
        voiceEnvelopeSpeeds = listOf(
            0.0f, 0.0f, 0.38235295f, 0.60294116f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f
        ),
        pairSharpness = listOf(0.59624976f, 0.0f, 0.0f, 0.6787497f, 0.0f, 0.0f),
        duoModSources = listOf(
            ModSource.VOICE_FM, ModSource.LFO, ModSource.LFO,
            ModSource.VOICE_FM, ModSource.OFF, ModSource.OFF
        ),
        hyperLfoA = 0.01f,
        hyperLfoB = 0.02875f,
        hyperLfoMode = HyperLfoMode.AND,
        hyperLfoLink = true,
        delayTime1 = 0.1675003f,
        delayTime2 = 0.22625016f,
        delayMod1 = 0.05875f,
        delayMod2 = 0.10875f,
        delayFeedback = 0.30750036f,
        delayMix = 0.51125044f,
        drive = 0.4f,
        distortionMix = .6f,
        createdAt = 1767461190433L
    )
}
