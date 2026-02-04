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
 * Swirly Dreams - Psychedelic FM modulation with LFO-driven delay.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SwirlyDreamsPatch : SynthPatch {
    override val id = "swirly_dreams"
    override val name = "Swirly Dreams"
    override val preset = SynthPreset(
        name = "Swirly Dreams",
        portValues = buildMap {
            val tunes = listOf(0.20f, 0.25f, 0.30f, 0.35f, 0.40f, 0.45f, 0.50f, 0.55f)
            tunes.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:tune_$i", PortValue.FloatValue(v)) }

            val modDepths = listOf(0.6f, 0.5f, 0.6f, 0.5f, 0.6f, 0.5f, 0.6f, 0.5f)
            modDepths.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:mod_depth_$i", PortValue.FloatValue(v)) }

            val envSpeeds = listOf(0.4f, 0.4f, 0.5f, 0.5f, 0.6f, 0.6f, 0.7f, 0.7f)
            envSpeeds.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:env_speed_$i", PortValue.FloatValue(v)) }

            val sharpness = listOf(0.4f, 0.5f, 0.4f, 0.5f)
            sharpness.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:pair_sharpness_$i", PortValue.FloatValue(v)) }

            val modSources = List(4) { ModSource.LFO }
            modSources.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:duo_mod_source_$i", PortValue.IntValue(v.ordinal)) }

            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.15f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.12f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.AND.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(true))

            val delayUri = DelayPlugin.URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.45f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.65f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.5f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.6f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.65f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.5f))
            put("$delayUri:mod_source_is_lfo", PortValue.BoolValue(true)) // Not in DelaySymbol yet?
            put("$delayUri:lfo_wave_is_triangle", PortValue.BoolValue(true)) // Not in DelaySymbol yet?

            val distUri = DistortionPlugin.URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.2f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.25f))
            
            put("org.balch.orpheus.plugins.voice:fm_structure_cross_quad", PortValue.BoolValue(false))
            put("org.balch.orpheus.plugins.voice:total_feedback", PortValue.FloatValue(0.35f))
        },
        createdAt = 0L
    )
}
