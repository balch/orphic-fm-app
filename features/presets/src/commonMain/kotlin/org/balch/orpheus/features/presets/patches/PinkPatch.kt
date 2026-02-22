package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DELAY_URI
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.plugin.symbols.STEREO_URI
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.plugin.symbols.WARPS_URI
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Pink - Psychedelic soundscape with cross-quad FM, heavy delay and warps.
 *
 * Pairs 0-1: Engines 10 & 5 with heavy FM and LFO modulation
 * Pair 2: Engine 8 with LFO-driven morph
 * Pair 3: Engine 13 at full morph depth with voice FM
 * Pairs 4-5: Silent (engine 0, no modulation)
 * Cross-quad FM structure with feedback, lush reverb, modulated delay, and warps processing.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class PinkPatch : SynthPatch {
    override val id = "pink"
    override val name = "Pink"
    override val preset = SynthPreset(
        name = "Pink",
        portValues = buildMap {
            val voiceUri = VOICE_URI

            // Tunes: detuned pairs for thick psychedelic sound
            val tunes = listOf(
                0.354f, 0.375f, 0.458f, 0.479f,
                0.458f, 0.479f, 0.361f, 0.379f,
                0.5f, 0.5f, 0.5f, 0.5f
            )
            tunes.forEachIndexed { i, v ->
                put("$voiceUri:tune_$i", PortValue.FloatValue(v))
            }

            // Mod depths: heavy FM on pairs 0-3, none on pairs 4-5
            val modDepths = listOf(
                0.43f, 0.43f, 0.08f, 0.08f,
                0.68f, 0.68f, 0.76f, 0.76f,
                0.0f, 0.0f, 0.0f, 0.0f
            )
            modDepths.forEachIndexed { i, v ->
                put("$voiceUri:mod_depth_$i", PortValue.FloatValue(v))
            }

            // Env speeds: fast attack on pair 0, slow release on pair 1
            val envSpeeds = listOf(
                0.19f, 0.0f, 1.0f, 1.0f,
                0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f
            )
            envSpeeds.forEachIndexed { i, v ->
                put("$voiceUri:env_speed_$i", PortValue.FloatValue(v))
            }

            // Duo engines
            val duoEngines = listOf(10, 5, 8, 13, 0, 0)
            duoEngines.forEachIndexed { i, v ->
                put("$voiceUri:duo_engine_$i", PortValue.IntValue(v))
            }

            // Duo sharpness
            val duoSharpness = listOf(0.50f, 0.62f, 0.25f, 0.64f, 0.50f, 0.50f)
            duoSharpness.forEachIndexed { i, v ->
                put("$voiceUri:duo_sharpness_$i", PortValue.FloatValue(v))
            }

            // Duo harmonics
            val duoHarmonics = listOf(0.32f, 0.08f, 0.75f, 0.06f, 0.0f, 0.0f)
            duoHarmonics.forEachIndexed { i, v ->
                put("$voiceUri:duo_harmonics_$i", PortValue.FloatValue(v))
            }

            // Duo morph
            val duoMorphs = listOf(0.68f, 0.69f, 0.59f, 1.0f, 0.0f, 0.0f)
            duoMorphs.forEachIndexed { i, v ->
                put("$voiceUri:duo_morph_$i", PortValue.FloatValue(v))
            }

            // Duo mod source levels
            val duoModSourceLevels = listOf(1.0f, 1.0f, 0.83f, 0.80f, 0.0f, 0.0f)
            duoModSourceLevels.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_level_$i", PortValue.FloatValue(v))
            }

            // Mod sources: VOICE_FM, LFO, LFO, VOICE_FM, VOICE_FM, VOICE_FM
            val modSources = listOf(
                ModSource.VOICE_FM, ModSource.LFO,
                ModSource.LFO, ModSource.VOICE_FM,
                ModSource.VOICE_FM, ModSource.VOICE_FM
            )
            modSources.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_$i", PortValue.IntValue(v.ordinal))
            }

            // Global voice: cross-quad FM structure with feedback
            put("$voiceUri:${VoiceSymbol.FM_STRUCTURE_CROSS_QUAD.symbol}", PortValue.BoolValue(true))
            put("$voiceUri:${VoiceSymbol.TOTAL_FEEDBACK.symbol}", PortValue.FloatValue(0.009f))

            // LFO: OR mode, unlinked, triangle waveform
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.52f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.27f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.OR.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(false))
            put("$lfoUri:${DuoLfoSymbol.TRIANGLE_MODE.symbol}", PortValue.BoolValue(true))

            // Reverb: lush and spacious
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.66f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.85f))
            put("$reverbUri:${ReverbSymbol.DAMPING.symbol}", PortValue.FloatValue(0.64f))
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.49f))

            // Delay: modulated stereo delay with heavy feedback
            val delayUri = DELAY_URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.08f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.354f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.14f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.35f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.72f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.57f))

            // Warps: subtle algorithm with mid timbre
            val warpsUri = WARPS_URI
            put("$warpsUri:${WarpsSymbol.MIX.symbol}", PortValue.FloatValue(0.50f))
            put("$warpsUri:${WarpsSymbol.ALGORITHM.symbol}", PortValue.FloatValue(0.12f))
            put("$warpsUri:${WarpsSymbol.TIMBRE.symbol}", PortValue.FloatValue(0.55f))
            put("$warpsUri:${WarpsSymbol.LEVEL1.symbol}", PortValue.FloatValue(0.5f))
            put("$warpsUri:${WarpsSymbol.LEVEL2.symbol}", PortValue.FloatValue(0.5f))

            // Distortion: moderate drive with mix
            val distUri = DISTORTION_URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.53f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.40f))

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
