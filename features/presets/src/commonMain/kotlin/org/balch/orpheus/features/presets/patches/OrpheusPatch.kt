package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.dsp.PortValue
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.delay.DelaySymbol
import org.balch.orpheus.plugins.distortion.DistortionPlugin
import org.balch.orpheus.plugins.distortion.DistortionSymbol
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoSymbol

/**
 * Warm Pad - Gentle, warm pad with subtle modulation.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class OrpheusPatch : SynthPatch {
    override val id = "orpheus"
    override val name = "Orpheus"
    override val preset = SynthPreset(
        name = "Orpheus",
        portValues = buildMap {
            val tunes = listOf(
                0.60874975f, 0.68999964f, 0.4812498f, 0.4962499f, 0.63999975f, 0.6599998f,
                0.49875003f, 0.55f, 0.75f, 0.82f, 0.89f, 0.96f
            )
            tunes.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:tune_$i", PortValue.FloatValue(v)) }

            val modDepths = listOf(
                0.59749967f, 0.59749967f, 0.26875004f, 0.26875004f, 0.48874983f, 0.48874983f,
                0.45749986f, 0.45749986f, 0.0f, 0.0f, 0.0f, 0.0f
            )
            modDepths.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:mod_depth_$i", PortValue.FloatValue(v)) }

            val envSpeeds = listOf(
                0.0f, 0.0f, 0.38235295f, 0.60294116f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f
            )
            envSpeeds.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:env_speed_$i", PortValue.FloatValue(v)) }

            val sharpness = listOf(0.59624976f, 0.0f, 0.0f, 0.6787497f, 0.0f, 0.0f)
            sharpness.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:pair_sharpness_$i", PortValue.FloatValue(v)) }

            val modSources = listOf(
                ModSource.VOICE_FM, ModSource.LFO, ModSource.LFO,
                ModSource.VOICE_FM, ModSource.OFF, ModSource.OFF
            )
            modSources.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:duo_mod_source_$i", PortValue.IntValue(v.ordinal)) }

            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.01f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.02875f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.AND.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(true))

            val delayUri = DelayPlugin.URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.1675003f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.22625016f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.05875f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.10875f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.30750036f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.51125044f))
            
            val distUri = DistortionPlugin.URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.4f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.6f))
        },
        createdAt = 1767461190433L
    )
}
