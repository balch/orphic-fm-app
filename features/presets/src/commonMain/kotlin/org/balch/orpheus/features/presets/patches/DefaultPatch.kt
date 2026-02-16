package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Default Patch - A musical starting point optimized for direct keyboard playing.
 *
 * Quads 0 and 1 have medium voice settings for cool, characterful sounds.
 * Quad 2 is tuned low with slow envelopes for a subtle underlying drone.
 * All engine 0 (default oscillator). Favor playing voices directly.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DefaultPatch : SynthPatch {
    override val id = "default"
    override val name = "Default"
    override val preset = SynthPreset(
        name = "Default",
        portValues = buildMap {
            val voiceUri = "org.balch.orpheus.plugins.voice"

            // Tunes: quads 0-1 span a nice chord, quad 2 sits low for drone
            val tunes = listOf(
                0.35f, 0.42f, 0.48f, 0.55f,   // Quad 0: mid range
                0.38f, 0.45f, 0.52f, 0.58f,    // Quad 1: slightly offset
                0.18f, 0.22f, 0.18f, 0.22f     // Quad 2: low drone
            )
            tunes.forEachIndexed { i, v ->
                put("$voiceUri:tune_$i", PortValue.FloatValue(v))
            }

            // Mod depths: medium on quads 0-1 for warmth, gentle on drone
            val modDepths = listOf(
                0.12f, 0.12f, 0.15f, 0.15f,
                0.10f, 0.10f, 0.12f, 0.12f,
                0.04f, 0.04f, 0.04f, 0.04f
            )
            modDepths.forEachIndexed { i, v ->
                put("$voiceUri:mod_depth_$i", PortValue.FloatValue(v))
            }

            // Fast envelopes (low = faster) for immediate feedback
            repeat(12) { i ->
                put("$voiceUri:env_speed_$i", PortValue.FloatValue(0.15f))
            }

            // Duo parameters: gentle detuning and character
            val duoMorphs = listOf(0.12f, 0.10f, 0.08f, 0.15f, 0.20f, 0.20f)
            duoMorphs.forEachIndexed { i, v ->
                put("$voiceUri:duo_morph_$i", PortValue.FloatValue(v))
            }

            val duoModSourceLevels = listOf(0.08f, 0.10f, 0.06f, 0.08f, 0.03f, 0.03f)
            duoModSourceLevels.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_level_$i", PortValue.FloatValue(v))
            }

            val duoSharpness = listOf(0.1f, 0.15f, 0.05f, 0.1f, 0.0f, 0.0f)
            duoSharpness.forEachIndexed { i, v ->
                put("$voiceUri:duo_sharpness_$i", PortValue.FloatValue(v))
            }

            // Mod sources: LFO on quads 0-1, OFF on drone (drone uses slow detuning only)
            val modSources = listOf(
                ModSource.LFO, ModSource.VOICE_FM,
                ModSource.VOICE_FM, ModSource.LFO,
                ModSource.OFF, ModSource.OFF
            )
            modSources.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_$i", PortValue.IntValue(v.ordinal))
            }

            // Drone quad: slightly lower volume so it sits underneath
            put("$voiceUri:quad_volume_2", PortValue.FloatValue(0.6f))

            // LFO: gentle
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.03f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.05f))

            // Light delay for space
            val delayUri = DelayPlugin.URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.55f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.40f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.25f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.20f))

            // Gentle distortion for warmth
            val distUri = DISTORTION_URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.15f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.3f))
        },
        createdAt = 0L
    )
}
