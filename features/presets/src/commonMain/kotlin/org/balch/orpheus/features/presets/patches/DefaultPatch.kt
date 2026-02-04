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
 * Default Patch - A rich, musical starting point optimized for pleasant exploration.
 * 
 * Tuning philosophy:
 * - Voices form a harmonious minor 7th chord spread across 4 octaves
 * - Slight detuning adds warmth without being dissonant
 * - Gentle delay adds space without overwhelming
 * - FM depth is subtle to add character without harshness
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DefaultPatch : SynthPatch {
    override val id = "default"
    override val name = "Default"
    override val preset = SynthPreset(
        name = "Default",
        portValues = buildMap {
            // Voice tunes create a minor 7th chord across octaves (Am7 voicing)
            val tunes = listOf(
                0.15f,  // Voice 0: ~82Hz (low A)
                0.20f,  // Voice 1: ~98Hz (low C)
                0.28f,  // Voice 2: ~123Hz (low E)
                0.32f,  // Voice 3: ~147Hz (low G)
                0.40f,  // Voice 4: ~165Hz (A)
                0.45f,  // Voice 5: ~196Hz (C)
                0.53f,  // Voice 6: ~247Hz (E)
                0.58f   // Voice 7: ~294Hz (G)
            )
            tunes.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:tune_$i", PortValue.FloatValue(v)) }

            val modDepths = listOf(0.05f, 0.05f, 0.08f, 0.08f, 0.10f, 0.10f, 0.12f, 0.12f)
            modDepths.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:mod_depth_$i", PortValue.FloatValue(v)) }

            val envSpeeds = listOf(0.3f, 0.3f, 0.3f, 0.3f, 0.4f, 0.4f, 0.4f, 0.4f)
            envSpeeds.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:env_speed_$i", PortValue.FloatValue(v)) }

            val sharpness = listOf(0.0f, 0.1f, 0.2f, 0.3f)
            sharpness.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:pair_sharpness_$i", PortValue.FloatValue(v)) }

            val modSources = listOf(
                ModSource.LFO, ModSource.VOICE_FM, ModSource.VOICE_FM, ModSource.LFO
            )
            modSources.forEachIndexed { i, v -> put("org.balch.orpheus.plugins.voice:duo_mod_source_$i", PortValue.IntValue(v.ordinal)) }

            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.0f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.1f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.OFF.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(false))

            val delayUri = DelayPlugin.URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.65f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.45f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.05f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.10f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.35f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.25f))
            put("$delayUri:mod_source_is_lfo", PortValue.BoolValue(true)) // Not in DelaySymbol yet?
            put("$delayUri:lfo_wave_is_triangle", PortValue.BoolValue(true)) // Not in DelaySymbol yet?

            val distUri = DistortionPlugin.URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.1f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.5f))
            
            put("org.balch.orpheus.plugins.voice:fm_structure_cross_quad", PortValue.BoolValue(false))
            put("org.balch.orpheus.plugins.voice:total_feedback", PortValue.FloatValue(0.0f))
        },
        createdAt = 0L
    )
}
