package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DELAY_URI
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.FLUX_URI
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.plugin.symbols.STEREO_URI
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * 6-7 - Engine 17 textures with Flux sequencing and deep modulation.
 *
 * Pairs 0-1: Engine 0 and Engine 17 with Flux and FM modulation
 * Pair 2: Engine 0 with LFO modulation
 * Pair 3: Engine 0 with no modulation
 * Pairs 4-5: Clean ascending melodic voices (engine 0, no FM)
 * Flux engaged for sequencing. Delay adds motion. Reverb and distortion for atmosphere.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SixSevenPatch : SynthPatch {
    override val id = "six_seven"
    override val name = "6-7"
    override val preset = SynthPreset(
        name = "6-7",
        portValues = buildMap {
            val voiceUri = "org.balch.orpheus.plugins.voice"

            // Tunes: varied across pairs
            val tunes = listOf(
                0.345f, 0.514f, 0.598f, 0.610f,    // Pair 0-1
                0.121f, 0.186f, 0.447f, 0.514f,    // Pair 2-3
                0.75f, 0.82f, 0.89f, 0.96f         // Pair 4-5: ascending
            )
            tunes.forEachIndexed { i, v ->
                put("$voiceUri:tune_$i", PortValue.FloatValue(v))
            }

            // Mod depths: strong FM on pairs 0-2, moderate on pair 3, clean on pairs 4-5
            val modDepths = listOf(
                0.44f, 0.44f, 0.58f, 0.58f,
                0.71f, 0.71f, 0.63f, 0.63f,
                0.00f, 0.00f, 0.00f, 0.00f
            )
            modDepths.forEachIndexed { i, v ->
                put("$voiceUri:mod_depth_$i", PortValue.FloatValue(v))
            }

            // Env speeds: gentle on pair 1, rest instant
            val envSpeeds = listOf(
                0.00f, 0.00f, 0.56f, 0.66f,
                0.00f, 0.00f, 0.00f, 0.00f,
                0.00f, 0.00f, 0.00f, 0.00f
            )
            envSpeeds.forEachIndexed { i, v ->
                put("$voiceUri:env_speed_$i", PortValue.FloatValue(v))
            }

            // Duo engines: Engine 17 on pair 1, engine 0 elsewhere
            val duoEngines = listOf(0, 17, 0, 0, 0, 0)
            duoEngines.forEachIndexed { i, v ->
                put("$voiceUri:duo_engine_$i", PortValue.IntValue(v))
            }

            // Duo sharpness
            val duoSharpness = listOf(0.40f, 0.50f, 0.40f, 0.50f, 0.00f, 0.00f)
            duoSharpness.forEachIndexed { i, v ->
                put("$voiceUri:duo_sharpness_$i", PortValue.FloatValue(v))
            }

            // Duo harmonics: engine 17 with harmonics on pair 1
            val duoHarmonics = listOf(0.00f, 0.60f, 0.00f, 0.00f, 0.00f, 0.00f)
            duoHarmonics.forEachIndexed { i, v ->
                put("$voiceUri:duo_harmonics_$i", PortValue.FloatValue(v))
            }

            // Duo morph
            val duoMorphs = listOf(0.40f, 0.35f, 0.45f, 0.30f, 0.00f, 0.00f)
            duoMorphs.forEachIndexed { i, v ->
                put("$voiceUri:duo_morph_$i", PortValue.FloatValue(v))
            }

            // Duo mod source levels
            val duoModSourceLevels = listOf(0.44f, 0.50f, 0.55f, 0.50f, 0.00f, 0.00f)
            duoModSourceLevels.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_level_$i", PortValue.FloatValue(v))
            }

            // Mod sources: FLUX on pair 0, VOICE_FM on pair 1, LFO on pair 2, OFF on pairs 3-5
            val modSources = listOf(
                ModSource.FLUX, ModSource.VOICE_FM,
                ModSource.LFO, ModSource.OFF,
                ModSource.OFF, ModSource.OFF
            )
            modSources.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_$i", PortValue.IntValue(v.ordinal))
            }

            // LFO: slow, linked, triangle, AND mode
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.02f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.02f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.AND.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(true))
            put("$lfoUri:${DuoLfoSymbol.TRIANGLE_MODE.symbol}", PortValue.BoolValue(true))

            // Reverb: warm and present
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.26f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.43f))
            put("$reverbUri:${ReverbSymbol.DAMPING.symbol}", PortValue.FloatValue(0.56f))
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.50f))

            // Delay: rhythmic motion with subtle mix
            val delayUri = DELAY_URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.168f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.614f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.66f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.27f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.60f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.06f))

            // Flux: engaged for sequencing
            val fluxUri = FLUX_URI
            put("$fluxUri:${FluxSymbol.SPREAD.symbol}", PortValue.FloatValue(0.30f))
            put("$fluxUri:${FluxSymbol.BIAS.symbol}", PortValue.FloatValue(0.18f))
            put("$fluxUri:${FluxSymbol.STEPS.symbol}", PortValue.FloatValue(0.34f))
            put("$fluxUri:${FluxSymbol.DEJAVU.symbol}", PortValue.FloatValue(0.64f))
            put("$fluxUri:${FluxSymbol.LENGTH.symbol}", PortValue.IntValue(5))
            put("$fluxUri:${FluxSymbol.SCALE.symbol}", PortValue.IntValue(2))
            put("$fluxUri:${FluxSymbol.RATE.symbol}", PortValue.FloatValue(0.20f))
            put("$fluxUri:${FluxSymbol.JITTER.symbol}", PortValue.FloatValue(0.00f))
            put("$fluxUri:${FluxSymbol.PROBABILITY.symbol}", PortValue.FloatValue(0.50f))
            put("$fluxUri:${FluxSymbol.MIX.symbol}", PortValue.FloatValue(0.47f))

            // Distortion: warm saturation
            val distUri = DISTORTION_URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.25f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.31f))

            // Stereo: wide voice placement
            val stereoUri = STEREO_URI
            put("$stereoUri:${StereoSymbol.MASTER_VOL.symbol}", PortValue.FloatValue(0.45f))
            val pans = listOf(0.0f, 0.0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f, 0.0f, 0.0f, 0.0f, 0.0f)
            pans.forEachIndexed { i, v ->
                put("$stereoUri:voice_pan_$i", PortValue.FloatValue(v))
            }
        },
        createdAt = 0L
    )
}
