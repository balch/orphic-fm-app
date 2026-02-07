package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.beats.BeatsPlugin
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.distortion.DistortionPlugin
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

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
            put("$delayUri:${DelaySymbol.MOD_SOURCE.symbol}", PortValue.BoolValue(true))
            put("$delayUri:${DelaySymbol.LFO_WAVEFORM.symbol}", PortValue.BoolValue(true))

            val distUri = DistortionPlugin.URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.2f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.25f))
            
            put("org.balch.orpheus.plugins.voice:fm_structure_cross_quad", PortValue.BoolValue(false))
            put("org.balch.orpheus.plugins.voice:total_feedback", PortValue.FloatValue(0.35f))

            // Drums — bypassed, bright metallic kit for psychedelic accents
            val drumUri = DrumPlugin.URI
            put("$drumUri:${DrumSymbol.BYPASS.symbol}", PortValue.BoolValue(true))
            put("$drumUri:${DrumSymbol.MIX.symbol}", PortValue.FloatValue(0.65f))
            put("$drumUri:${DrumSymbol.BD_FREQ.symbol}", PortValue.FloatValue(0.6f))
            put("$drumUri:${DrumSymbol.BD_TONE.symbol}", PortValue.FloatValue(0.65f))
            put("$drumUri:${DrumSymbol.BD_DECAY.symbol}", PortValue.FloatValue(0.55f))
            put("$drumUri:${DrumSymbol.BD_P4.symbol}", PortValue.FloatValue(0.5f))
            put("$drumUri:${DrumSymbol.BD_P5.symbol}", PortValue.FloatValue(0.7f))
            put("$drumUri:${DrumSymbol.SD_FREQ.symbol}", PortValue.FloatValue(0.4f))
            put("$drumUri:${DrumSymbol.SD_TONE.symbol}", PortValue.FloatValue(0.7f))
            put("$drumUri:${DrumSymbol.SD_DECAY.symbol}", PortValue.FloatValue(0.55f))
            put("$drumUri:${DrumSymbol.SD_P4.symbol}", PortValue.FloatValue(0.7f))
            put("$drumUri:${DrumSymbol.HH_FREQ.symbol}", PortValue.FloatValue(0.5f))
            put("$drumUri:${DrumSymbol.HH_TONE.symbol}", PortValue.FloatValue(0.65f))
            put("$drumUri:${DrumSymbol.HH_DECAY.symbol}", PortValue.FloatValue(0.45f))
            put("$drumUri:${DrumSymbol.HH_P4.symbol}", PortValue.FloatValue(0.6f))

            // Beats — trippy fast pattern with randomness
            val beatsUri = BeatsPlugin.URI
            put("$beatsUri:${BeatsSymbol.X.symbol}", PortValue.FloatValue(0.7f))
            put("$beatsUri:${BeatsSymbol.Y.symbol}", PortValue.FloatValue(0.7f))
            put("$beatsUri:${BeatsSymbol.BPM.symbol}", PortValue.FloatValue(135f))
            put("$beatsUri:${BeatsSymbol.MIX.symbol}", PortValue.FloatValue(0.65f))
            put("$beatsUri:${BeatsSymbol.RANDOMNESS.symbol}", PortValue.FloatValue(0.25f))
            put("$beatsUri:${BeatsSymbol.SWING.symbol}", PortValue.FloatValue(0.1f))
        },
        createdAt = 0L
    )
}
