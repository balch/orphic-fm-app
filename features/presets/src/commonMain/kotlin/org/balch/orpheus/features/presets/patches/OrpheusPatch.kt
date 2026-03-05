package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.plugin.symbols.STEREO_URI
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Orpheus Patch - The signature sound. Multi-engine layering with stereo depth.
 *
 * Pairs 0-1: FM-rich voices with character (engines 0, 10)
 * Pair 2: Warm modulated mid-range (engine 8)
 * Pair 3: Wild textural layer at max depth (engine 13)
 * Pairs 4-5: Clean ascending melodic voices (engine 0, no FM)
 * Reverb and distortion provide atmosphere; delay/warps/flux off by default.
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

            // Tunes: varied across pairs for rich layering
            val tunes = listOf(
                0.61f, 0.69f, 0.48f, 0.50f,    // Pair 0-1: mid-high FM voices
                0.64f, 0.66f, 0.50f, 0.55f,    // Pair 2-3: mid range
                0.75f, 0.82f, 0.89f, 0.96f     // Pair 4-5: clean ascending
            )
            tunes.forEachIndexed { i, v ->
                put("$voiceUri:tune_$i", PortValue.FloatValue(v))
            }

            // Mod depths: rich FM on pairs 0-2, max on pair 3, clean on pairs 4-5
            val modDepths = listOf(
                0.60f, 0.60f, 0.27f, 0.27f,
                0.49f, 0.49f, 1.00f, 1.00f,
                0.00f, 0.00f, 0.00f, 0.00f
            )
            modDepths.forEachIndexed { i, v ->
                put("$voiceUri:mod_depth_$i", PortValue.FloatValue(v))
            }

            // Env speeds: fast on most, gentle on pair 1, medium on pair 3
            val envSpeeds = listOf(
                0.00f, 0.00f, 0.15f, 0.10f,
                0.00f, 0.00f, 0.53f, 0.43f,
                0.00f, 0.00f, 0.00f, 0.00f
            )
            envSpeeds.forEachIndexed { i, v ->
                put("$voiceUri:env_speed_$i", PortValue.FloatValue(v))
            }

            // Duo engines: varied for timbral diversity
            val duoEngines = listOf(0, 10, 8, 13, 0, 0)
            duoEngines.forEachIndexed { i, v ->
                put("$voiceUri:duo_engine_$i", PortValue.IntValue(v))
            }

            // Duo sharpness: character across active pairs
            val duoSharpness = listOf(0.60f, 0.56f, 0.47f, 0.78f, 0.00f, 0.00f)
            duoSharpness.forEachIndexed { i, v ->
                put("$voiceUri:duo_sharpness_$i", PortValue.FloatValue(v))
            }

            // Duo harmonics: engine-specific timbral character
            val duoHarmonics = listOf(0.15f, 0.45f, 0.60f, 0.42f, 0.00f, 0.00f)
            duoHarmonics.forEachIndexed { i, v ->
                put("$voiceUri:duo_harmonics_$i", PortValue.FloatValue(v))
            }

            // Duo morph: shapes timbre per engine — critical for non-default engines
            val duoMorphs = listOf(0.30f, 0.50f, 0.45f, 0.92f, 0.15f, 0.15f)
            duoMorphs.forEachIndexed { i, v ->
                put("$voiceUri:duo_morph_$i", PortValue.FloatValue(v))
            }

            // Duo mod source levels: how much the mod source affects timbre
            val duoModSourceLevels = listOf(0.25f, 0.30f, 0.35f, 0.06f, 0.00f, 0.00f)
            duoModSourceLevels.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_level_$i", PortValue.FloatValue(v))
            }

            // Mod sources: VOICE_FM on pair 0, LFO on pairs 1-2, OFF on pairs 3-5
            val modSources = listOf(
                ModSource.VOICE_FM, ModSource.LFO,
                ModSource.LFO, ModSource.OFF,
                ModSource.OFF, ModSource.OFF
            )
            modSources.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_$i", PortValue.IntValue(v.ordinal))
            }

            // LFO: slow, linked, triangle for smooth modulation
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.01f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.029f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.AND.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(true))
            put("$lfoUri:${DuoLfoSymbol.SHAPE.symbol}", PortValue.FloatValue(1f))

            // Reverb: warm and present
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.26f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.43f))
            put("$reverbUri:${ReverbSymbol.DAMPING.symbol}", PortValue.FloatValue(0.56f))
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.50f))

            // Distortion: warm saturation (moderate to avoid blowout with many voices)
            val distUri = DISTORTION_URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.30f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.40f))

            // Stereo: wide voice placement
            val stereoUri = STEREO_URI
            val pans = listOf(0.0f, 0.0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f, 0.0f, 0.0f, 0.0f, 0.0f)
            pans.forEachIndexed { i, v ->
                put("$stereoUri:voice_pan_$i", PortValue.FloatValue(v))
            }
        },
        createdAt = 0L
    )
}
