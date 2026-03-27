package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.WarpsSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BEATS_URI
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DELAY_URI
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.FLUX_URI
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
import org.balch.orpheus.core.plugin.symbols.GRAINS_URI
import org.balch.orpheus.core.plugin.symbols.GrainsSymbol
import org.balch.orpheus.core.plugin.symbols.RESONATOR_URI
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.plugin.symbols.STEREO_URI
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.plugin.symbols.WARPS_URI
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Pink — "Another Brick in the Wall" — Dm at 104 BPM.
 *
 * Duo 0 (voices 0-1): Floyd bass — STRING engine, D2+A2, hard left
 * Duo 1 (voices 2-3): Rhythm chords — Engine 0, D3+F3, center-left
 * Duo 2 (voices 4-5): Atmospheric textures — ADDITIVE, A3+C4, wide stereo
 * Duo 3 (voices 6-7): Gilmour lead — FM engine, D4+A4, center-right
 * Duo 4 (voices 8-9): REPL — Engine 0, D3+A3, centered
 * Duo 5 (voices 10-11): REPL — Engine 0, F3+D4, centered
 *
 * All effect mixes OFF by default. User dials in:
 *   - Warps: carrier=SYNTH, modulator=LFO → drone sweep when mix > 0
 *   - Flux: slow evolving random pitch variation
 *   - Resonator: sympathetic shimmer
 *   - Grains: frozen ambient textures
 *
 * LFO: glacial sweep (0.03/0.07 Hz) for atmospheric drone modulation.
 * Delay: shorter feedback (0.42) to prevent Warps harmonic buildup.
 * Reverb: dark hall, moderate tail.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class PinkPatch : SynthPatch {
    override val id = "pink"
    override val name = "Pink"
    override val preset = SynthPreset(
        name = "Pink",
        portValues = buildMap {
            val v = VOICE_URI

            // ═══ VOICE TUNES ═══
            // All in D minor: D, F, A, C, Bb
            // tune = (midiNote - 33 - pitchMult) / 48
            // pitchMult = [-12,-12, 0,0, 0,0, 12,12, 0,0, 0,0]
            val tunes = listOf(
                0.354f, 0.500f, // Duo 0: D2(38), A2(45) — bass root+5th
                0.354f, 0.417f, // Duo 1: D3(50), F3(53) — minor chord
                0.500f, 0.563f, // Duo 2: A3(57), C4(60) — 5th+7th tension
                0.354f, 0.500f, // Duo 3: D4(62), A4(69) — high octave root+5th
                0.354f, 0.500f, // REPL: D3, A3
                0.417f, 0.604f  // REPL: F3, D4
            )
            tunes.forEachIndexed { i, t -> put("$v:tune_$i", PortValue.FloatValue(t)) }

            // ═══ DUO ENGINES ═══
            // UI picker ordinals: 0=OSC, 5=FM, 9=ADD, 11=STR, 12=MOD
            val engines = listOf(11, 0, 9, 5, 0, 0)
            engines.forEachIndexed { i, e -> put("$v:duo_engine_$i", PortValue.IntValue(e)) }

            // ═══ DUO SHARPNESS ═══
            val sharpness = listOf(
                0.15f,  // Duo 0: round bass attack
                0.40f,  // Duo 1: medium rhythm bite
                0.30f,  // Duo 2: soft texture onset
                0.55f,  // Duo 3: bright lead attack
                0.40f, 0.40f
            )
            sharpness.forEachIndexed { i, s -> put("$v:duo_sharpness_$i", PortValue.FloatValue(s)) }

            // ═══ DUO HARMONICS ═══
            val harmonics = listOf(
                0.20f,  // Duo 0: fundamental-heavy bass
                0.35f,  // Duo 1: mid warmth
                0.60f,  // Duo 2: rich overtones
                0.45f,  // Duo 3: singing FM tone
                0.50f, 0.50f
            )
            harmonics.forEachIndexed { i, h -> put("$v:duo_harmonics_$i", PortValue.FloatValue(h)) }

            // ═══ DUO MORPH ═══
            val morphs = listOf(
                0.70f,  // Duo 0: bowed sustain (STRING morph)
                0.30f,  // Duo 1: clean triangle/square blend
                0.65f,  // Duo 2: dense additive partials
                0.55f,  // Duo 3: FM modulation index
                0.50f, 0.50f
            )
            morphs.forEachIndexed { i, m -> put("$v:duo_morph_$i", PortValue.FloatValue(m)) }

            // ═══ MODULATION SOURCES ═══
            // Duo 0: LFO for slow bass vibrato
            // Duo 1: OFF (clean rhythm)
            // Duo 2: FLUX (evolving textures when Flux enabled)
            // Duo 3: LFO (Gilmour vibrato)
            val modSources = listOf(
                ModSource.LFO, ModSource.OFF,
                ModSource.FLUX, ModSource.LFO,
                ModSource.OFF, ModSource.OFF
            )
            modSources.forEachIndexed { i, s ->
                put("$v:duo_mod_source_$i", PortValue.IntValue(s.ordinal))
            }

            // ═══ MOD DEPTHS ═══
            val modDepths = listOf(
                0.08f, 0.08f,   // Duo 0: subtle bass vibrato
                0.0f, 0.0f,     // Duo 1: off
                0.40f, 0.40f,   // Duo 2: Flux-driven when enabled
                0.15f, 0.15f,   // Duo 3: gentle Gilmour vibrato
                0.0f, 0.0f, 0.0f, 0.0f
            )
            modDepths.forEachIndexed { i, d -> put("$v:mod_depth_$i", PortValue.FloatValue(d)) }

            // ═══ MOD SOURCE LEVELS ═══
            val modLevels = listOf(0.50f, 0.0f, 0.55f, 0.45f, 0.0f, 0.0f)
            modLevels.forEachIndexed { i, l ->
                put("$v:duo_mod_source_level_$i", PortValue.FloatValue(l))
            }

            // ═══ ENVELOPE SPEEDS ═══
            val envSpeeds = listOf(
                0.50f, 0.50f,   // Duo 0: medium bass swell
                0.15f, 0.15f,   // Duo 1: snappy rhythm
                0.75f, 0.75f,   // Duo 2: slow ambient fade-in
                0.30f, 0.30f,   // Duo 3: medium lead
                0.0f, 0.0f, 0.0f, 0.0f
            )
            envSpeeds.forEachIndexed { i, e -> put("$v:env_speed_$i", PortValue.FloatValue(e)) }

            // ═══ VOICE HOLDS ═══
            for (i in 0..11) put("$v:voice_hold_$i", PortValue.FloatValue(0f))

            // ═══ GLOBAL VOICE ═══
            put("$v:${VoiceSymbol.FM_STRUCTURE_CROSS_QUAD.symbol}", PortValue.BoolValue(false))
            put("$v:${VoiceSymbol.TOTAL_FEEDBACK.symbol}", PortValue.FloatValue(0.008f))
            put("$v:${VoiceSymbol.VIBRATO.symbol}", PortValue.FloatValue(0.06f))
            put("$v:${VoiceSymbol.COUPLING.symbol}", PortValue.FloatValue(0.10f))

            // ═══ LFO ═══
            // Glacial sweep — designed for Warps drone modulation
            // When LFO is selected as Warps modulator, these rates create
            // slow atmospheric sweeps across the synth carrier
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.03f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.07f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.AND.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(false))
            put("$lfoUri:${DuoLfoSymbol.SHAPE.symbol}", PortValue.FloatValue(0.9f))  // nearly pure triangle

            // ═══ FLUX ═══
            // Slow evolving random — ready for when user enables it
            val fluxUri = FLUX_URI
            put("$fluxUri:${FluxSymbol.RATE.symbol}", PortValue.FloatValue(0.20f))
            put("$fluxUri:${FluxSymbol.SPREAD.symbol}", PortValue.FloatValue(0.55f))
            put("$fluxUri:${FluxSymbol.BIAS.symbol}", PortValue.FloatValue(0.50f))
            put("$fluxUri:${FluxSymbol.STEPS.symbol}", PortValue.FloatValue(0.35f))
            put("$fluxUri:${FluxSymbol.JITTER.symbol}", PortValue.FloatValue(0.20f))
            put("$fluxUri:${FluxSymbol.DEJAVU.symbol}", PortValue.FloatValue(0.60f))
            put("$fluxUri:${FluxSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))

            // ═══ GRAINS ═══
            val grainsUri = GRAINS_URI
            put("$grainsUri:${GrainsSymbol.POSITION.symbol}", PortValue.FloatValue(0.40f))
            put("$grainsUri:${GrainsSymbol.SIZE.symbol}", PortValue.FloatValue(0.65f))
            put("$grainsUri:${GrainsSymbol.PITCH.symbol}", PortValue.FloatValue(0.50f))
            put("$grainsUri:${GrainsSymbol.DENSITY.symbol}", PortValue.FloatValue(0.55f))
            put("$grainsUri:${GrainsSymbol.TEXTURE.symbol}", PortValue.FloatValue(0.75f))
            put("$grainsUri:${GrainsSymbol.DRY_WET.symbol}", PortValue.FloatValue(0.0f))
            put("$grainsUri:${GrainsSymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.35f))
            put("$grainsUri:${GrainsSymbol.REVERB.symbol}", PortValue.FloatValue(0.60f))

            // ═══ WARPS ═══
            // Carrier=SYNTH, Modulator=LFO — the drone sweep setup
            // At mix=0 (default), Warps is silent. User dials mix up to hear
            // the LFO slowly sweeping the synth through the wavefolder.
            val warpsUri = WARPS_URI
            put("$warpsUri:${WarpsSymbol.ALGORITHM.symbol}", PortValue.FloatValue(1.5f))  // wavefolder
            put("$warpsUri:${WarpsSymbol.TIMBRE.symbol}", PortValue.FloatValue(0.45f))
            put("$warpsUri:${WarpsSymbol.LEVEL1.symbol}", PortValue.FloatValue(0.45f))     // carrier (synth)
            put("$warpsUri:${WarpsSymbol.LEVEL2.symbol}", PortValue.FloatValue(0.35f))     // modulator (LFO) — lower to balance 7x level mismatch
            put("$warpsUri:${WarpsSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))
            put("$warpsUri:${WarpsSymbol.CARRIER_SOURCE.symbol}", PortValue.IntValue(WarpsSource.SYNTH.ordinal))
            put("$warpsUri:${WarpsSymbol.MODULATOR_SOURCE.symbol}", PortValue.IntValue(WarpsSource.LFO.ordinal))

            // ═══ RESONATOR ═══
            val resoUri = RESONATOR_URI
            put("$resoUri:${ResonatorSymbol.STRUCTURE.symbol}", PortValue.FloatValue(0.40f))
            put("$resoUri:${ResonatorSymbol.BRIGHTNESS.symbol}", PortValue.FloatValue(0.50f))
            put("$resoUri:${ResonatorSymbol.DAMPING.symbol}", PortValue.FloatValue(0.45f))
            put("$resoUri:${ResonatorSymbol.POSITION.symbol}", PortValue.FloatValue(0.35f))
            put("$resoUri:${ResonatorSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))

            // ═══ DELAY ═══
            // Rhythmic dotted-eighth at 104 BPM. Lower feedback (0.42) to
            // prevent harmonic buildup when Warps is adding harmonics.
            val delayUri = DELAY_URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.18f))
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.36f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.05f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.10f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.42f))
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.30f))

            // ═══ REVERB ═══
            // Dark hall — moderate tail, not cavernous
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.55f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.70f))
            put("$reverbUri:${ReverbSymbol.DAMPING.symbol}", PortValue.FloatValue(0.65f))
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.50f))

            // ═══ DISTORTION ═══
            // Light warmth — just enough edge
            val distUri = DISTORTION_URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.25f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.15f))

            // ═══ TEMPO ═══
            put("org.balch.orpheus.plugins.tempo:bpm", PortValue.FloatValue(104f))

            // ═══ DRUMS ═══
            // "Another Brick" groove — steady kick, sparse snare
            val beatsUri = BEATS_URI
            put("$beatsUri:${BeatsSymbol.X.symbol}", PortValue.FloatValue(0.40f))
            put("$beatsUri:${BeatsSymbol.Y.symbol}", PortValue.FloatValue(0.45f))
            put("$beatsUri:${BeatsSymbol.RANDOMNESS.symbol}", PortValue.FloatValue(0.10f))
            put("$beatsUri:${BeatsSymbol.DENSITY_0.symbol}", PortValue.FloatValue(0.50f))
            put("$beatsUri:${BeatsSymbol.DENSITY_1.symbol}", PortValue.FloatValue(0.30f))
            put("$beatsUri:${BeatsSymbol.DENSITY_2.symbol}", PortValue.FloatValue(0.45f))

            // ═══ STEREO PANNING ═══
            // Bass hard left, rhythm center-left, textures wide, lead center-right
            val stereoUri = STEREO_URI
            val pans = listOf(
                -0.70f, -0.50f, // Duo 0: bass LEFT
                -0.20f, -0.10f, // Duo 1: rhythm center-left
                -0.60f, 0.60f,  // Duo 2: textures wide
                0.15f, 0.30f,   // Duo 3: lead center-right
                -0.3f, 0.3f, -0.15f, 0.15f  // REPL: moderate spread
            )
            pans.forEachIndexed { i, p -> put("$stereoUri:voice_pan_$i", PortValue.FloatValue(p)) }
        },
        createdAt = 0L
    )
}
