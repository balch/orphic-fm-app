package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Clean Patch - Musical starting point with character but no chaos.
 *
 * All engine 0. Tunes form a spread chord across three registers.
 * Light reverb and delay for space. Fast envelopes for immediate response.
 * Good for direct keyboard playing, REPL, and live coding.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class CleanPatch : SynthPatch {
    override val id = "clean"
    override val name = "Clean"
    override val preset = SynthPreset(
        name = "Clean",
        portValues = buildMap {
            val voiceUri = "org.balch.orpheus.plugins.voice"

            // Tunes: spread chord — low pair, mid pair, high pair
            val tunes = listOf(
                0.35f, 0.42f, 0.40f, 0.47f,    // Pair 0-1: lower mid
                0.52f, 0.57f, 0.55f, 0.62f,    // Pair 2-3: mid
                0.65f, 0.72f, 0.70f, 0.77f     // Pair 4-5: upper
            )
            tunes.forEachIndexed { i, v ->
                put("$voiceUri:tune_$i", PortValue.FloatValue(v))
            }

            // Mod depths: gentle FM warmth, decreasing up the register
            val modDepths = listOf(
                0.15f, 0.15f, 0.12f, 0.12f,
                0.10f, 0.10f, 0.08f, 0.08f,
                0.05f, 0.05f, 0.03f, 0.03f
            )
            modDepths.forEachIndexed { i, v ->
                put("$voiceUri:mod_depth_$i", PortValue.FloatValue(v))
            }

            // Fast envelopes for immediate response
            repeat(12) { i ->
                put("$voiceUri:env_speed_$i", PortValue.FloatValue(0.0f))
            }

            // Duo morph: gentle timbre variation
            val duoMorphs = listOf(0.20f, 0.15f, 0.18f, 0.12f, 0.10f, 0.10f)
            duoMorphs.forEachIndexed { i, v ->
                put("$voiceUri:duo_morph_$i", PortValue.FloatValue(v))
            }

            // Mod sources: gentle LFO on lower pairs, off on upper
            val modSources = listOf(
                ModSource.LFO, ModSource.LFO,
                ModSource.LFO, ModSource.OFF,
                ModSource.OFF, ModSource.OFF
            )
            modSources.forEachIndexed { i, v ->
                put("$voiceUri:duo_mod_source_$i", PortValue.IntValue(v.ordinal))
            }

            // LFO: gentle and slow
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.02f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.035f))
            put("$lfoUri:${DuoLfoSymbol.TRIANGLE_MODE.symbol}", PortValue.BoolValue(true))

            // Light reverb for space
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.20f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.35f))
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.50f))

            // Subtle delay for depth
            val delayUri = DelayPlugin.URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.45f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.60f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.20f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.15f))
        },
        createdAt = 0L
    )
}
