package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.FLUX_URI
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.plugin.symbols.WARPS_URI
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.distortion.DistortionPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Swirly Dreams - Psychedelic chaos. Everything cranked.
 *
 * All engine 0. Designed for the hold knobs — max hold, heavy Flux,
 * aggressive LFO, cross-quad FM, feedback, and saturated effects.
 * Let it rip and surf the chaos.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SwirlyDreamsPatch : SynthPatch {
    override val id = "swirly_dreams"
    override val name = "Swirly Dreams"
    override val preset = SynthPreset(
        name = "Swirly Dreams",
        portValues = buildMap {
            val voiceUri = "org.balch.orpheus.plugins.voice"

            // Tunes: tight clusters for beating/interference patterns
            val tunes = listOf(
                0.25f, 0.26f, 0.30f, 0.31f,
                0.45f, 0.46f, 0.50f, 0.51f,
                0.65f, 0.66f, 0.70f, 0.71f
            )
            tunes.forEachIndexed { i, v ->
                put("$voiceUri:tune_$i", PortValue.FloatValue(v))
            }

            // Mod depths: deep FM for aggressive harmonics
            val modDepths = listOf(
                0.55f, 0.60f, 0.50f, 0.55f,
                0.60f, 0.65f, 0.55f, 0.60f,
                0.50f, 0.55f, 0.45f, 0.50f
            )
            modDepths.forEachIndexed { i, v ->
                put("$voiceUri:mod_depth_$i", PortValue.FloatValue(v))
            }

            // Env speeds: fast and varied for chaotic envelopes
            val envSpeeds = listOf(
                0.50f, 0.55f, 0.60f, 0.65f,
                0.45f, 0.50f, 0.55f, 0.60f,
                0.70f, 0.75f, 0.80f, 0.85f
            )
            envSpeeds.forEachIndexed { i, v ->
                put("$voiceUri:env_speed_$i", PortValue.FloatValue(v))
            }

            // Duo parameters: heavy detuning and deep modulation
            val duoMorphs = listOf(0.40f, 0.35f, 0.45f, 0.38f, 0.42f, 0.36f)
            duoMorphs.forEachIndexed { i, v ->
                put("$voiceUri:duo_morph_$i", PortValue.FloatValue(v))
            }

            val duoModSourceLevels = listOf(0.60f, 0.55f, 0.65f, 0.50f, 0.58f, 0.52f)
            duoModSourceLevels.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_level_$i", PortValue.FloatValue(v))
            }

            val duoSharpness = listOf(0.45f, 0.50f, 0.40f, 0.55f, 0.35f, 0.48f)
            duoSharpness.forEachIndexed { i, v ->
                put("$voiceUri:duo_sharpness_$i", PortValue.FloatValue(v))
            }

            // Mod sources: all LFO for maximum swirl
            List(6) { ModSource.LFO }.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_$i", PortValue.IntValue(v.ordinal))
            }

            // Holds start at 0 (forced by PresetLoader) — user cranks them up

            // Cross-quad FM and feedback for wild interactions
            put("$voiceUri:fm_structure_cross_quad", PortValue.BoolValue(true))
            put("$voiceUri:total_feedback", PortValue.FloatValue(0.35f))
            put("$voiceUri:coupling", PortValue.FloatValue(0.3f))

            // LFO: fast and complex
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.18f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.14f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.OR.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(true))

            // Flux: heavy, fast, unpredictable
            val fluxUri = FLUX_URI
            put("$fluxUri:${FluxSymbol.MIX.symbol}", PortValue.FloatValue(0.50f))
            put("$fluxUri:${FluxSymbol.RATE.symbol}", PortValue.FloatValue(0.6f))
            put("$fluxUri:${FluxSymbol.SPREAD.symbol}", PortValue.FloatValue(0.7f))
            put("$fluxUri:${FluxSymbol.STEPS.symbol}", PortValue.FloatValue(0.4f))
            put("$fluxUri:${FluxSymbol.DEJAVU.symbol}", PortValue.FloatValue(0.3f))
            put("$fluxUri:${FluxSymbol.JITTER.symbol}", PortValue.FloatValue(0.4f))
            put("$fluxUri:${FluxSymbol.PROBABILITY.symbol}", PortValue.FloatValue(0.7f))

            // Delay: long, modulated, high feedback for spiraling echoes
            val delayUri = DelayPlugin.URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.45f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.65f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.40f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.50f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.65f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.45f))

            // Warps: engaged for extra harmonic mayhem
            val warpsUri = WARPS_URI
            put("$warpsUri:${WarpsSymbol.MIX.symbol}", PortValue.FloatValue(0.35f))
            put("$warpsUri:${WarpsSymbol.ALGORITHM.symbol}", PortValue.IntValue(2))
            put("$warpsUri:${WarpsSymbol.TIMBRE.symbol}", PortValue.FloatValue(0.6f))

            // Distortion: gritty saturation
            val distUri = DistortionPlugin.URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.35f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.4f))

            // Reverb: vast, washy
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.50f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.8f))
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.9f))
        },
        createdAt = 0L
    )
}
