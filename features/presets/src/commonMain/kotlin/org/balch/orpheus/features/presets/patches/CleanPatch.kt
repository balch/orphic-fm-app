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
 * Clean Patch - Good for REPL
 *
 * */
@Inject
@ContributesIntoSet(AppScope::class)
class CleanPatch : SynthPatch {
    override val id = "clean"
    override val name = "Clean"
    override val preset = SynthPreset(
        name = "Clean",
        portValues = buildMap {
            // Voice tunes create a minor 7th chord across octaves (Am7 voicing)
            val tunes = listOf(
                0.15f, 0.20f, 0.28f, 0.32f, 
                0.40f, 0.45f, 0.53f, 0.58f
            )
            tunes.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:tune_$i", PortValue.FloatValue(v)) }
            
            // Other voice params initialized to default (0)
            List(8) { 0f }.forEachIndexed { i, v -> 
                 put("org.balch.orpheus.plugins.voice:mod_depth_$i", PortValue.FloatValue(v))
                 put("org.balch.orpheus.plugins.voice:env_speed_$i", PortValue.FloatValue(v))
            }
            List(4) { 0f }.forEachIndexed { i, v ->
                 put("org.balch.orpheus.plugins.voice:pair_sharpness_$i", PortValue.FloatValue(v))
            }
            List(4) { ModSource.OFF }.forEachIndexed { i, v ->
                 put("org.balch.orpheus.plugins.voice:duo_mod_source_$i", PortValue.IntValue(v.ordinal))
            }

            // Hyper LFO
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.0f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.0f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.OFF.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(false))

            // Delay
            val delayUri = DelayPlugin.URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0f))
            put("$delayUri:mod_source_is_lfo", PortValue.BoolValue(true)) // Not in DelaySymbol yet?
            put("$delayUri:lfo_wave_is_triangle", PortValue.BoolValue(true)) // Not in DelaySymbol yet?

            // Global
            val distUri = DistortionPlugin.URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.5f))
            
            put("org.balch.orpheus.plugins.voice:fm_structure_cross_quad", PortValue.BoolValue(false))
            put("org.balch.orpheus.plugins.voice:total_feedback", PortValue.FloatValue(0.0f))
        },
        createdAt = 0L
    )
}
