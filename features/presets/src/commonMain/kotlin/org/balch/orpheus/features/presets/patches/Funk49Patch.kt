package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.WarpsSource
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BASS_URI
import org.balch.orpheus.core.plugin.symbols.BEATS_URI
import org.balch.orpheus.core.plugin.symbols.BassSymbol
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DELAY_URI
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.DRUM_URI
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.FLUX_URI
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
import org.balch.orpheus.core.plugin.symbols.GRAINS_URI
import org.balch.orpheus.core.plugin.symbols.GrainsSymbol
import org.balch.orpheus.core.plugin.symbols.POLY_LFO_URI
import org.balch.orpheus.core.plugin.symbols.PolyLfoSymbol
import org.balch.orpheus.core.plugin.symbols.RESONATOR_URI
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.plugin.symbols.STEREO_URI
import org.balch.orpheus.core.plugin.symbols.TIDES_URI
import org.balch.orpheus.core.plugin.symbols.TidesSymbol
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.plugin.symbols.WARPS_URI
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Funk49 — James Gang meets slow-motion P-Funk drone.
 *
 * Key of E minor at 98 BPM — laid-back, greasy groove.
 *
 * Duo 0 (voices 0-1): Funk bass foundation — Engine 0, E2+B2, hard left
 * Duo 1 (voices 2-3): Clavinet snap — FM engine, E3+G3, center-left
 * Duo 2 (voices 4-5): Wah drone pad — ADDITIVE, B3+D3, wide stereo
 * Duo 3 (voices 6-7): Sizzle lead — FM engine, E4+G4, center-right
 * Duo 4 (voices 8-9): Bowed funk sustain — STRING, F#3+B3, center-left
 * Duo 5 (voices 10-11): Shimmer harmonics — ADDITIVE, D4+G3, center-right
 *
 * Bass voice: VCF Acid engine, E2 root, minor pentatonic, slow mutation.
 * Synced to same key as keyboard voices for harmonic coherence.
 *
 * Warps: carrier=BASS, modulator=SYNTH — bass fed through wavefolder
 *   with synth voices as modulator. Creates thick cross-modulated funk.
 *
 * Tides: slow looping function generator, voice-gated, control range.
 *   Glacial slope/shape sweeps create evolving timbral drift.
 *
 * PolyLFO: medium rate with coupling — 4 phase-shifted channels
 *   breathe organic movement into all quads simultaneously.
 *
 * All duos use LFO mod source so the DuoLFO/PolyLFO system drives
 * the slow funk pulse across every quad.
 *
 * Flux: lazy random walk for subtle pitch warble on the drone.
 * Reverb: warm room, short tail — keeps it tight and funky.
 * Delay: slapback funk echo — short, rhythmic, no mud.
 * Distortion: light tube warmth for that analog grit.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class Funk49Patch : SynthPatch {
    override val id = "funk49"
    override val name = "Funk 49"
    override val preset = SynthPreset(
        name = "Funk 49",
        portValues = buildMap {
            val v = VOICE_URI

            // ═══ VOICE TUNES ═══
            // All in E minor: E, G, B, D, F#
            // tune = (midiNote - 33 - pitchMult) / 48
            // pitchMult = [-12,-12, 0,0, 0,0, 12,12, 0,0, 0,0]
            val tunes = listOf(
                0.396f, 0.542f, // Duo 0: E2(40), B2(47) — bass root+5th
                0.396f, 0.458f, // Duo 1: E3(52), G3(55) — minor 3rd snap
                0.542f, 0.354f, // Duo 2: B3(59), D3(50) — 5th+7th drone tension
                0.396f, 0.458f, // Duo 3: E4(64), G4(67) — high octave sizzle
                0.438f, 0.542f, // Duo 4: F#3(54), B3(59) — bowed 2nd+5th tension
                0.604f, 0.458f  // Duo 5: D4(62), G3(55) — 7th+3rd shimmer
            )
            tunes.forEachIndexed { i, t -> put("$v:tune_$i", PortValue.FloatValue(t)) }

            // ═══ DUO ENGINES ═══
            // UI picker ordinals: 0=OSC, 5=FM, 9=ADD, 11=STR, 12=MOD
            val engines = listOf(0, 5, 9, 5, 11, 9)
            engines.forEachIndexed { i, e -> put("$v:duo_engine_$i", PortValue.IntValue(e)) }

            // ═══ DUO SHARPNESS ═══
            // Funk needs bite — sharper attacks across the board
            val sharpness = listOf(
                0.55f,  // Duo 0: punchy bass pluck
                0.70f,  // Duo 1: clavinet snap — high attack
                0.20f,  // Duo 2: soft drone onset
                0.65f,  // Duo 3: biting lead
                0.30f,  // Duo 4: medium-soft bowed attack
                0.25f   // Duo 5: gentle shimmer onset
            )
            sharpness.forEachIndexed { i, s -> put("$v:duo_sharpness_$i", PortValue.FloatValue(s)) }

            // ═══ DUO HARMONICS ═══
            val harmonics = listOf(
                0.20f,  // Duo 0: moderate bass feedback (lower to avoid squeal at low pitches)
                0.55f,  // Duo 1: bright clav overtones
                0.70f,  // Duo 2: rich additive spectrum for the drone
                0.50f,  // Duo 3: mid-bright FM lead
                0.35f,  // Duo 4: warm bowed string overtones
                0.65f   // Duo 5: bright additive shimmer
            )
            harmonics.forEachIndexed { i, h -> put("$v:duo_harmonics_$i", PortValue.FloatValue(h)) }

            // ═══ DUO MORPH ═══
            val morphs = listOf(
                0.35f,  // Duo 0: slightly squared bass — funky edge
                0.60f,  // Duo 1: FM mod index for clavinet bite
                0.75f,  // Duo 2: dense additive cloud — the drone body
                0.45f,  // Duo 3: moderate FM — singing lead
                0.80f,  // Duo 4: deep bowed sustain (STRING morph high)
                0.55f   // Duo 5: mid-dense additive partials
            )
            morphs.forEachIndexed { i, m -> put("$v:duo_morph_$i", PortValue.FloatValue(m)) }

            // ═══ MODULATION SOURCES ═══
            // All active duos use LFO — the PolyLFO/DuoLFO system drives everything
            val modSources = listOf(
                ModSource.LFO, ModSource.LFO,
                ModSource.LFO, ModSource.LFO,
                ModSource.LFO, ModSource.FLUX
            )
            modSources.forEachIndexed { i, s ->
                put("$v:duo_mod_source_$i", PortValue.IntValue(s.ordinal))
            }

            // ═══ MOD DEPTHS ═══
            // Deeper on the drone, subtle on rhythm, medium on bass/lead
            val modDepths = listOf(
                0.12f, 0.12f,   // Duo 0: gentle bass wobble
                0.08f, 0.08f,   // Duo 1: tight clav — minimal drift
                0.35f, 0.35f,   // Duo 2: deep drone modulation — the slow evolve
                0.18f, 0.18f,   // Duo 3: moderate lead expression
                0.25f, 0.25f,   // Duo 4: bowed LFO swell
                0.30f, 0.30f    // Duo 5: Flux-driven shimmer drift
            )
            modDepths.forEachIndexed { i, d -> put("$v:mod_depth_$i", PortValue.FloatValue(d)) }

            // ═══ MOD SOURCE LEVELS ═══
            val modLevels = listOf(0.45f, 0.30f, 0.65f, 0.50f, 0.55f, 0.50f)
            modLevels.forEachIndexed { i, l ->
                put("$v:duo_mod_source_level_$i", PortValue.FloatValue(l))
            }

            // ═══ ENVELOPE SPEEDS ═══
            // Funk is about contrast: snappy rhythm + slow-blooming drone
            val envSpeeds = listOf(
                0.25f, 0.25f,   // Duo 0: quick bass pluck with moderate release
                0.10f, 0.10f,   // Duo 1: ultra-snappy clavinet
                0.85f, 0.85f,   // Duo 2: very slow drone bloom
                0.35f, 0.35f,   // Duo 3: medium lead sustain
                0.65f, 0.65f,   // Duo 4: slow bowed swell
                0.50f, 0.50f    // Duo 5: medium shimmer sustain
            )
            envSpeeds.forEachIndexed { i, e -> put("$v:env_speed_$i", PortValue.FloatValue(e)) }

            // ═══ VOICE HOLDS ═══
            for (i in 0..11) put("$v:voice_hold_$i", PortValue.FloatValue(0f))

            // ═══ GLOBAL VOICE ═══
            put("$v:${VoiceSymbol.FM_STRUCTURE_CROSS_QUAD.symbol}", PortValue.BoolValue(true))
            put("$v:${VoiceSymbol.TOTAL_FEEDBACK.symbol}", PortValue.FloatValue(0.015f))
            put("$v:${VoiceSymbol.VIBRATO.symbol}", PortValue.FloatValue(0.03f))
            put("$v:${VoiceSymbol.COUPLING.symbol}", PortValue.FloatValue(0.20f))

            // ═══ LFO ═══
            // Slow funk pulse — OR mode for wider combined waveform
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.08f))
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.05f))
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.OR.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(false))
            put("$lfoUri:${DuoLfoSymbol.SHAPE.symbol}", PortValue.FloatValue(0.6f))  // rounded triangle — organic pulse

            // ═══ POLY LFO ═══
            // 4-channel organic funk breathing — coupled channels for cohesion
            val polyUri = POLY_LFO_URI
            put("$polyUri:${PolyLfoSymbol.SHAPE.symbol}", PortValue.FloatValue(0.60f))
            put("$polyUri:${PolyLfoSymbol.SHAPE_SPREAD.symbol}", PortValue.FloatValue(0.40f))
            put("$polyUri:${PolyLfoSymbol.SPREAD.symbol}", PortValue.FloatValue(0.55f))
            put("$polyUri:${PolyLfoSymbol.COUPLING.symbol}", PortValue.FloatValue(0.40f))
            put("$polyUri:${PolyLfoSymbol.RATE.symbol}", PortValue.FloatValue(0.16f))
            put("$polyUri:${PolyLfoSymbol.BYPASS.symbol}", PortValue.FloatValue(0.0f))

            // ═══ TIDES ═══
            // Slow looping function generator — glacial timbral drift
            // Voice-gated so it breathes with the notes
            val tidesUri = TIDES_URI
            put("$tidesUri:${TidesSymbol.FREQUENCY.symbol}", PortValue.FloatValue(0.20f))
            put("$tidesUri:${TidesSymbol.SLOPE.symbol}", PortValue.FloatValue(0.65f))
            put("$tidesUri:${TidesSymbol.SHAPE.symbol}", PortValue.FloatValue(0.40f))
            put("$tidesUri:${TidesSymbol.SMOOTHNESS.symbol}", PortValue.FloatValue(0.70f))
            put("$tidesUri:${TidesSymbol.SHIFT.symbol}", PortValue.FloatValue(0.15f))
            put("$tidesUri:${TidesSymbol.MIX.symbol}", PortValue.FloatValue(0.45f))
            put("$tidesUri:${TidesSymbol.CLOCK_OFFSET.symbol}", PortValue.FloatValue(0.0f))
            put("$tidesUri:${TidesSymbol.RAMP_MODE.symbol}", PortValue.IntValue(1))       // Looping
            put("$tidesUri:${TidesSymbol.OUTPUT_MODE.symbol}", PortValue.IntValue(1))      // Amplitude
            put("$tidesUri:${TidesSymbol.RANGE.symbol}", PortValue.IntValue(0))            // Control rate
            put("$tidesUri:${TidesSymbol.GATE_SOURCE.symbol}", PortValue.IntValue(0))      // Voice gate
            put("$tidesUri:${TidesSymbol.CLOCK_SOURCE.symbol}", PortValue.IntValue(0))     // Internal

            // ═══ BASS ═══
            // VCF Acid in E minor pentatonic — slow mutating acid line
            // Root matches keyboard voices for harmonic lock
            val bassUri = BASS_URI
            put("$bassUri:${BassSymbol.ENGINE.symbol}", PortValue.IntValue(0))             // VCF Acid
            put("$bassUri:${BassSymbol.ROOT_NOTE.symbol}", PortValue.IntValue(40))         // E2
            put("$bassUri:${BassSymbol.SCALE.symbol}", PortValue.IntValue(1))              // Minor Pentatonic
            put("$bassUri:${BassSymbol.CLOCK_DIV.symbol}", PortValue.IntValue(1))          // 2x — half-time groove
            put("$bassUri:${BassSymbol.STEP_COUNT.symbol}", PortValue.IntValue(8))         // 8 steps — tight loop
            put("$bassUri:${BassSymbol.MUTATION.symbol}", PortValue.FloatValue(0.15f))     // subtle pattern drift
            put("$bassUri:${BassSymbol.CUTOFF.symbol}", PortValue.FloatValue(0.35f))       // dark filter — opens with envelope
            put("$bassUri:${BassSymbol.RESONANCE.symbol}", PortValue.FloatValue(0.45f))    // squelchy resonance
            put("$bassUri:${BassSymbol.ENVELOPE.symbol}", PortValue.FloatValue(0.80f))     // deep envelope sweep — the funk
            put("$bassUri:${BassSymbol.OVERDRIVE.symbol}", PortValue.FloatValue(0.20f))    // warm tube grit
            put("$bassUri:${BassSymbol.COMPRESSOR.symbol}", PortValue.FloatValue(0.55f))   // glue the bass together
            put("$bassUri:${BassSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))           // off by default — user dials in
            put("$bassUri:${BassSymbol.LFO_MIX.symbol}", PortValue.FloatValue(0.30f))     // LFO modulates filter for wah effect
            put("$bassUri:${BassSymbol.TRIGGER_SOURCE.symbol}", PortValue.IntValue(0))     // Off — internal sequencer
            put("$bassUri:${BassSymbol.PITCH_SOURCE.symbol}", PortValue.IntValue(0))       // Off — internal sequencer
            put("$bassUri:${BassSymbol.TIMBRE_SOURCE.symbol}", PortValue.IntValue(0))      // Off
            put("$bassUri:${BassSymbol.ACCENT_AMOUNT.symbol}", PortValue.FloatValue(0.60f))
            put("$bassUri:${BassSymbol.JITTER.symbol}", PortValue.FloatValue(0.08f))       // tiny timing humanization
            put("$bassUri:${BassSymbol.FX_SEND.symbol}", PortValue.FloatValue(0.40f))      // send to effects chain

            // ═══ FLUX ═══
            // Lazy random walk — subtle pitch/timbre warble on the drone
            val fluxUri = FLUX_URI
            put("$fluxUri:${FluxSymbol.RATE.symbol}", PortValue.FloatValue(0.12f))
            put("$fluxUri:${FluxSymbol.SPREAD.symbol}", PortValue.FloatValue(0.40f))
            put("$fluxUri:${FluxSymbol.BIAS.symbol}", PortValue.FloatValue(0.50f))
            put("$fluxUri:${FluxSymbol.STEPS.symbol}", PortValue.FloatValue(0.25f))
            put("$fluxUri:${FluxSymbol.JITTER.symbol}", PortValue.FloatValue(0.15f))
            put("$fluxUri:${FluxSymbol.DEJAVU.symbol}", PortValue.FloatValue(0.70f))
            put("$fluxUri:${FluxSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))

            // ═══ GRAINS ═══
            // Frozen funk textures — ready for user to dial in
            val grainsUri = GRAINS_URI
            put("$grainsUri:${GrainsSymbol.POSITION.symbol}", PortValue.FloatValue(0.55f))
            put("$grainsUri:${GrainsSymbol.SIZE.symbol}", PortValue.FloatValue(0.50f))
            put("$grainsUri:${GrainsSymbol.PITCH.symbol}", PortValue.FloatValue(0.50f))
            put("$grainsUri:${GrainsSymbol.DENSITY.symbol}", PortValue.FloatValue(0.45f))
            put("$grainsUri:${GrainsSymbol.TEXTURE.symbol}", PortValue.FloatValue(0.60f))
            put("$grainsUri:${GrainsSymbol.DRY_WET.symbol}", PortValue.FloatValue(0.0f))
            put("$grainsUri:${GrainsSymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.25f))
            put("$grainsUri:${GrainsSymbol.REVERB.symbol}", PortValue.FloatValue(0.40f))

            // ═══ WARPS ═══
            // Carrier=BASS, Modulator=SYNTH — the cross-modulation funk engine.
            // Bass signal through the wavefolder with synth voices pushing it.
            // At mix=0 (default), Warps is silent. Dial up for filthy cross-mod.
            val warpsUri = WARPS_URI
            put("$warpsUri:${WarpsSymbol.ALGORITHM.symbol}", PortValue.FloatValue(1.0f))   // ring mod zone — funky sidebands
            put("$warpsUri:${WarpsSymbol.TIMBRE.symbol}", PortValue.FloatValue(0.55f))
            put("$warpsUri:${WarpsSymbol.LEVEL1.symbol}", PortValue.FloatValue(0.50f))     // carrier (bass)
            put("$warpsUri:${WarpsSymbol.LEVEL2.symbol}", PortValue.FloatValue(0.40f))     // modulator (synth)
            put("$warpsUri:${WarpsSymbol.MIX.symbol}", PortValue.FloatValue(0.25f))        // subtle cross-mod always on
            put("$warpsUri:${WarpsSymbol.CARRIER_SOURCE.symbol}", PortValue.IntValue(WarpsSource.BASS.ordinal))
            put("$warpsUri:${WarpsSymbol.MODULATOR_SOURCE.symbol}", PortValue.IntValue(WarpsSource.SYNTH.ordinal))

            // ═══ RESONATOR ═══
            val resoUri = RESONATOR_URI
            put("$resoUri:${ResonatorSymbol.STRUCTURE.symbol}", PortValue.FloatValue(0.55f))
            put("$resoUri:${ResonatorSymbol.BRIGHTNESS.symbol}", PortValue.FloatValue(0.60f))
            put("$resoUri:${ResonatorSymbol.DAMPING.symbol}", PortValue.FloatValue(0.35f))
            put("$resoUri:${ResonatorSymbol.POSITION.symbol}", PortValue.FloatValue(0.45f))
            put("$resoUri:${ResonatorSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))

            // ═══ DELAY ═══
            // Slapback funk echo — tight, rhythmic, no mud
            val delayUri = DELAY_URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.12f))     // short slapback
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.24f))     // dotted feel
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.08f))
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.12f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.30f))   // low feedback — stays tight
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.20f))

            // ═══ REVERB ═══
            // Warm room — short tail, keeps the funk pocket tight
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.35f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.40f))
            put("$reverbUri:${ReverbSymbol.DAMPING.symbol}", PortValue.FloatValue(0.55f))
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.45f))

            // ═══ DISTORTION ═══
            // Tube warmth — analog grit without harshness
            val distUri = DISTORTION_URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.16f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.16f))

            // ═══ TEMPO ═══
            put("org.balch.orpheus.plugins.tempo:bpm", PortValue.FloatValue(120f))

            // ═══ 808 DRUMS ═══
            // V1.2 engines: PD kick, NES snare, TRN hat
            val drumUri = DRUM_URI
            put("$drumUri:${DrumSymbol.BD_ENGINE.symbol}", PortValue.IntValue(18))         // PD (Phase Distortion)
            put("$drumUri:${DrumSymbol.BD_FREQ.symbol}", PortValue.FloatValue(0.24f))
            put("$drumUri:${DrumSymbol.BD_TONE.symbol}", PortValue.FloatValue(0.31f))
            put("$drumUri:${DrumSymbol.BD_DECAY.symbol}", PortValue.FloatValue(0.54f))
            put("$drumUri:${DrumSymbol.BD_P4.symbol}", PortValue.FloatValue(0.63f))
            put("$drumUri:${DrumSymbol.SD_ENGINE.symbol}", PortValue.IntValue(22))         // NES (Chiptune)
            put("$drumUri:${DrumSymbol.SD_FREQ.symbol}", PortValue.FloatValue(0.35f))
            put("$drumUri:${DrumSymbol.SD_TONE.symbol}", PortValue.FloatValue(0.50f))
            put("$drumUri:${DrumSymbol.SD_DECAY.symbol}", PortValue.FloatValue(0.50f))
            put("$drumUri:${DrumSymbol.SD_P4.symbol}", PortValue.FloatValue(0.70f))
            put("$drumUri:${DrumSymbol.HH_ENGINE.symbol}", PortValue.IntValue(20))         // TRN (Wave Terrain)
            put("$drumUri:${DrumSymbol.HH_FREQ.symbol}", PortValue.FloatValue(0.29f))
            put("$drumUri:${DrumSymbol.HH_TONE.symbol}", PortValue.FloatValue(0.59f))
            put("$drumUri:${DrumSymbol.HH_DECAY.symbol}", PortValue.FloatValue(0.42f))
            put("$drumUri:${DrumSymbol.HH_P4.symbol}", PortValue.FloatValue(0.68f))

            // ═══ RHYTHM (BEATS) ═══
            val beatsUri = BEATS_URI
            put("$beatsUri:${BeatsSymbol.X.symbol}", PortValue.FloatValue(0.49f))
            put("$beatsUri:${BeatsSymbol.Y.symbol}", PortValue.FloatValue(0.20f))
            put("$beatsUri:${BeatsSymbol.RANDOMNESS.symbol}", PortValue.FloatValue(0.15f))
            put("$beatsUri:${BeatsSymbol.DENSITY_0.symbol}", PortValue.FloatValue(0.49f))  // BD
            put("$beatsUri:${BeatsSymbol.DENSITY_1.symbol}", PortValue.FloatValue(0.70f))  // SD
            put("$beatsUri:${BeatsSymbol.DENSITY_2.symbol}", PortValue.FloatValue(0.38f))  // HH

            // ═══ STEREO PANNING ═══
            // Bass anchored left, clav center-left, drone wide, lead center-right
            val stereoUri = STEREO_URI
            val pans = listOf(
                -0.60f, -0.40f, // Duo 0: bass LEFT
                -0.25f, -0.15f, // Duo 1: clav center-left
                -0.70f, 0.70f,  // Duo 2: drone pad WIDE
                0.20f, 0.35f,   // Duo 3: lead center-right
                -0.35f, -0.15f, // Duo 4: bowed strings center-left
                0.15f, 0.40f    // Duo 5: shimmer center-right
            )
            pans.forEachIndexed { i, p -> put("$stereoUri:voice_pan_$i", PortValue.FloatValue(p)) }
        },
        createdAt = 0L
    )
}
