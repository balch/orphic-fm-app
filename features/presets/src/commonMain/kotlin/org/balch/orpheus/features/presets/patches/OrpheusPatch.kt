package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.FLUX_URI
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Orpheus Patch - Evolving drones with depth and atmosphere.
 *
 * All engine 0. Designed for the hold knobs — bring up quad holds
 * and let the voices sustain and evolve through Flux modulation and LFO.
 * Ideal for ambient, meditative soundscapes.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class OrpheusPatch : SynthPatch {
    override val id = "orpheus"
    override val name = "Orpheus"
    override val preset = SynthPreset(
        name = "Orpheus",
        portValues = buildMap {
            val voiceUri = "org.balch.orpheus.plugins.voice"

            // Tunes: spread across a wide range for rich drone harmonics
            val tunes = listOf(
                0.20f, 0.27f, 0.35f, 0.42f,
                0.38f, 0.45f, 0.52f, 0.60f,
                0.55f, 0.62f, 0.70f, 0.78f
            )
            tunes.forEachIndexed { i, v ->
                put("$voiceUri:tune_$i", PortValue.FloatValue(v))
            }

            // Mod depths: rich FM character across all voices
            val modDepths = listOf(
                0.25f, 0.25f, 0.30f, 0.30f,
                0.20f, 0.20f, 0.25f, 0.25f,
                0.15f, 0.15f, 0.20f, 0.20f
            )
            modDepths.forEachIndexed { i, v ->
                put("$voiceUri:mod_depth_$i", PortValue.FloatValue(v))
            }

            // Env speeds: slow-to-medium for evolving sustain
            val envSpeeds = listOf(
                0.15f, 0.15f, 0.20f, 0.20f,
                0.18f, 0.18f, 0.22f, 0.22f,
                0.12f, 0.12f, 0.15f, 0.15f
            )
            envSpeeds.forEachIndexed { i, v ->
                put("$voiceUri:env_speed_$i", PortValue.FloatValue(v))
            }

            // Duo parameters: warm detuning and modulation depth
            val duoMorphs = listOf(0.18f, 0.15f, 0.20f, 0.22f, 0.25f, 0.18f)
            duoMorphs.forEachIndexed { i, v ->
                put("$voiceUri:duo_morph_$i", PortValue.FloatValue(v))
            }

            val duoModSourceLevels = listOf(0.30f, 0.25f, 0.35f, 0.28f, 0.20f, 0.22f)
            duoModSourceLevels.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_level_$i", PortValue.FloatValue(v))
            }

            val duoSharpness = listOf(0.15f, 0.10f, 0.20f, 0.12f, 0.08f, 0.10f)
            duoSharpness.forEachIndexed { i, v ->
                put("$voiceUri:duo_sharpness_$i", PortValue.FloatValue(v))
            }

            // Mod sources: mix of LFO and Flux for evolving character
            val modSources = listOf(
                ModSource.LFO, ModSource.FLUX,
                ModSource.LFO, ModSource.FLUX,
                ModSource.LFO, ModSource.FLUX
            )
            modSources.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_$i", PortValue.IntValue(v.ordinal))
            }

            // Holds start at 0 (forced by PresetLoader) — user brings them up

            // LFO: slow, linked for coherent modulation
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.02f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.035f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.AND.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(true))

            // Flux: engaged for evolving pitch sequences
            val fluxUri = FLUX_URI
            put("$fluxUri:${FluxSymbol.MIX.symbol}", PortValue.FloatValue(0.25f))
            put("$fluxUri:${FluxSymbol.RATE.symbol}", PortValue.FloatValue(0.3f))
            put("$fluxUri:${FluxSymbol.SPREAD.symbol}", PortValue.FloatValue(0.4f))
            put("$fluxUri:${FluxSymbol.STEPS.symbol}", PortValue.FloatValue(0.6f))
            put("$fluxUri:${FluxSymbol.DEJAVU.symbol}", PortValue.FloatValue(0.5f))
            put("$fluxUri:${FluxSymbol.JITTER.symbol}", PortValue.FloatValue(0.1f))

            // Delay: atmospheric
            val delayUri = DelayPlugin.URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.55f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.70f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.08f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.12f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.40f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.35f))

            // Reverb: spacious
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.35f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.6f))
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.7f))

            // Distortion: warm saturation
            val distUri = DISTORTION_URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.25f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.4f))
        },
        createdAt = 0L
    )
}
