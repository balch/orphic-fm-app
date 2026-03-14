package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
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
import org.balch.orpheus.core.plugin.symbols.TTS_URI
import org.balch.orpheus.core.plugin.symbols.TtsSymbol
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.plugin.symbols.WARPS_URI
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin

/**
 * Pink — Dark psychedelic soundscape inspired by Pink Floyd's "One of These Days" / "Echoes".
 *
 * Quad 0 (voices 0-1): Deep ominous bass — STRING engine detuned low, slow FM, "The Wall" rumble
 * Quad 1 (voices 2-3): Soaring lead — FM engine with LFO modulation, Gilmour-like sustain
 * Quad 2 (voices 4-5): Texture/effects bank — GRAIN + PARTICLE for ambient swells, Flux-driven
 * Quad 3 (voices 6-7): Pad/atmosphere — CHORD + WAVETABLE for wide stereo shimmer
 * REPL (voices 8-11): User-controlled, tuned for experimentation
 *
 * Cross-quad FM structure: Quad 2 modulates Quad 0, Quad 3 modulates Quad 1
 * Warps: Synth × Drums ring mod at low mix for metallic undertone
 * Grains: Frozen shimmer with long position sweep
 * Delay: Pink Floyd-style rhythmic dotted-eighth with long feedback tail
 * Reverb: Cavernous hall — dark, long, diffuse
 * Drums: Slow hypnotic beat with deep kick and metallic hat
 * TTS: "One of These Days" voice through phaser/delay/reverb
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
            // Quad 0: deep bass, detuned pair (low octave)
            // Quad 1: lead, slight detune for chorus (mid octave)
            // Quad 2: textures, spread wide for granular interest
            // Quad 3: pad, tight detuning for shimmer
            val tunes = listOf(
                0.30f, 0.31f,   // Q0: deep bass pair, barely detuned
                0.48f, 0.49f,   // Q1: lead pair, slight chorus
                0.42f, 0.55f,   // Q2: textures, spread wide
                0.46f, 0.47f,   // Q3: pad pair, tight shimmer
                0.5f, 0.5f, 0.5f, 0.5f  // REPL: centered
            )
            tunes.forEachIndexed { i, t -> put("$v:tune_$i", PortValue.FloatValue(t)) }

            // ═══ DUO ENGINES ═══
            // 10=STRING, 4=FM, 9=GRAIN, 12=PARTICLE, 14=CHORD, 15=WAVETABLE
            val engines = listOf(10, 4, 9, 12, 14, 15)
            engines.forEachIndexed { i, e -> put("$v:duo_engine_$i", PortValue.IntValue(e)) }

            // ═══ DUO SHARPNESS (waveform character) ═══
            val sharpness = listOf(
                0.20f,  // Q0: smooth string attack
                0.55f,  // Q1: bright FM carrier
                0.35f,  // Q2: soft grain windows
                0.70f,  // Q3: rhythmic chord attack
                0.45f, 0.50f  // REPL pairs
            )
            sharpness.forEachIndexed { i, s -> put("$v:duo_sharpness_$i", PortValue.FloatValue(s)) }

            // ═══ DUO HARMONICS ═══
            val harmonics = listOf(
                0.15f,  // Q0: fundamental-heavy bass
                0.40f,  // Q1: mid-harmonic lead
                0.72f,  // Q2: rich grain texture
                0.25f,  // Q3: warm chord voicing
                0.50f, 0.50f
            )
            harmonics.forEachIndexed { i, h -> put("$v:duo_harmonics_$i", PortValue.FloatValue(h)) }

            // ═══ DUO MORPH ═══
            val morphs = listOf(
                0.75f,  // Q0: bowed string sustain (high morph = bow)
                0.60f,  // Q1: FM feedback (medium for singing tone)
                0.82f,  // Q2: dense granular cloud
                0.45f,  // Q3: chord inversion sweep midpoint
                0.50f, 0.50f
            )
            morphs.forEachIndexed { i, m -> put("$v:duo_morph_$i", PortValue.FloatValue(m)) }

            // ═══ MODULATION SOURCES ═══
            // Q0: Voice FM (cross-quad from Q2 textures)
            // Q1: LFO (slow Gilmour vibrato)
            // Q2: Flux (random evolving textures)
            // Q3: LFO (shimmer pulse)
            val modSources = listOf(
                ModSource.VOICE_FM, ModSource.LFO,
                ModSource.FLUX, ModSource.LFO,
                ModSource.OFF, ModSource.OFF
            )
            modSources.forEachIndexed { i, s ->
                put("$v:duo_mod_source_$i", PortValue.IntValue(s.ordinal))
            }

            // ═══ MOD DEPTHS ═══
            val modDepths = listOf(
                0.35f, 0.35f,   // Q0: subtle FM rumble from Q2
                0.12f, 0.12f,   // Q1: gentle LFO vibrato
                0.55f, 0.55f,   // Q2: Flux-driven texture evolution
                0.20f, 0.20f,   // Q3: LFO shimmer pulse
                0.0f, 0.0f, 0.0f, 0.0f
            )
            modDepths.forEachIndexed { i, d -> put("$v:mod_depth_$i", PortValue.FloatValue(d)) }

            // ═══ MOD SOURCE LEVELS ═══
            val modLevels = listOf(0.80f, 0.45f, 0.70f, 0.55f, 0.0f, 0.0f)
            modLevels.forEachIndexed { i, l ->
                put("$v:duo_mod_source_level_$i", PortValue.FloatValue(l))
            }

            // ═══ ENVELOPE SPEEDS ═══
            // Q0: slow attack for bass swell, Q1: medium for lead, Q2: very slow ambient
            val envSpeeds = listOf(
                0.6f, 0.6f,     // Q0: slow bass swell
                0.25f, 0.25f,   // Q1: medium lead attack
                0.85f, 0.85f,   // Q2: very slow ambient fade
                0.45f, 0.45f,   // Q3: medium pad
                0.0f, 0.0f, 0.0f, 0.0f
            )
            envSpeeds.forEachIndexed { i, e -> put("$v:env_speed_$i", PortValue.FloatValue(e)) }

            // ═══ VOICE HOLDS (quad hold targets — start at 0, user cranks up) ═══
            // Small non-zero bias on Q0 so bass drones gently even without keys
            for (i in 0..11) put("$v:voice_hold_$i", PortValue.FloatValue(0f))

            // ═══ GLOBAL VOICE ═══
            put("$v:${VoiceSymbol.FM_STRUCTURE_CROSS_QUAD.symbol}", PortValue.BoolValue(true))
            put("$v:${VoiceSymbol.TOTAL_FEEDBACK.symbol}", PortValue.FloatValue(0.012f))
            put("$v:${VoiceSymbol.VIBRATO.symbol}", PortValue.FloatValue(0.08f))
            put("$v:${VoiceSymbol.COUPLING.symbol}", PortValue.FloatValue(0.15f))

            // ═══ LFO ═══
            // Slow asymmetric triangle for that breathing Pink Floyd modulation
            val lfoUri = DuoLfoPlugin.URI
            put("$lfoUri:${DuoLfoSymbol.FREQ_A.symbol}", PortValue.FloatValue(0.18f))  // very slow A
            put("$lfoUri:${DuoLfoSymbol.FREQ_B.symbol}", PortValue.FloatValue(0.31f))  // slightly faster B
            put("$lfoUri:${DuoLfoSymbol.MODE.symbol}", PortValue.IntValue(HyperLfoMode.OR.ordinal))
            put("$lfoUri:${DuoLfoSymbol.LINK.symbol}", PortValue.BoolValue(false))
            put("$lfoUri:${DuoLfoSymbol.SHAPE.symbol}", PortValue.FloatValue(0.8f))     // mostly triangle

            // ═══ FLUX (Marbles-style random sequencer) ═══
            // Slow evolving random voltages feeding Q2 textures
            val fluxUri = FLUX_URI
            put("$fluxUri:${FluxSymbol.RATE.symbol}", PortValue.FloatValue(0.25f))
            put("$fluxUri:${FluxSymbol.SPREAD.symbol}", PortValue.FloatValue(0.65f))
            put("$fluxUri:${FluxSymbol.BIAS.symbol}", PortValue.FloatValue(0.50f))
            put("$fluxUri:${FluxSymbol.STEPS.symbol}", PortValue.FloatValue(0.40f))
            put("$fluxUri:${FluxSymbol.JITTER.symbol}", PortValue.FloatValue(0.30f))
            put("$fluxUri:${FluxSymbol.DEJAVU.symbol}", PortValue.FloatValue(0.55f))
            put("$fluxUri:${FluxSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))  // off by default, user dials in

            // ═══ GRAINS (Clouds-style granular) ═══
            // Frozen shimmer — long grains, slow position drift, heavy reverb
            val grainsUri = GRAINS_URI
            put("$grainsUri:${GrainsSymbol.POSITION.symbol}", PortValue.FloatValue(0.35f))
            put("$grainsUri:${GrainsSymbol.SIZE.symbol}", PortValue.FloatValue(0.72f))     // large grains
            put("$grainsUri:${GrainsSymbol.PITCH.symbol}", PortValue.FloatValue(0.50f))     // unity pitch
            put("$grainsUri:${GrainsSymbol.DENSITY.symbol}", PortValue.FloatValue(0.60f))   // medium density
            put("$grainsUri:${GrainsSymbol.TEXTURE.symbol}", PortValue.FloatValue(0.80f))   // smooth crossfade
            put("$grainsUri:${GrainsSymbol.DRY_WET.symbol}", PortValue.FloatValue(0.0f))    // off, user dials in
            put("$grainsUri:${GrainsSymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.45f))  // moderate feedback
            put("$grainsUri:${GrainsSymbol.REVERB.symbol}", PortValue.FloatValue(0.70f))    // internal reverb

            // ═══ WARPS (Meta-Modulator) ═══
            // Synth × Drums through analog ring mod — metallic Pink Floyd undertone
            val warpsUri = WARPS_URI
            put("$warpsUri:${WarpsSymbol.ALGORITHM.symbol}", PortValue.FloatValue(0.25f))   // analog ring mod zone
            put("$warpsUri:${WarpsSymbol.TIMBRE.symbol}", PortValue.FloatValue(0.62f))      // warm modulation depth
            put("$warpsUri:${WarpsSymbol.LEVEL1.symbol}", PortValue.FloatValue(0.55f))      // carrier drive
            put("$warpsUri:${WarpsSymbol.LEVEL2.symbol}", PortValue.FloatValue(0.50f))      // modulator drive
            put("$warpsUri:${WarpsSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))          // off, user dials in
            put("$warpsUri:${WarpsSymbol.CARRIER_SOURCE.symbol}", PortValue.IntValue(0))     // SYNTH
            put("$warpsUri:${WarpsSymbol.MODULATOR_SOURCE.symbol}", PortValue.IntValue(1))   // DRUMS

            // ═══ RESONATOR ═══
            // Metallic sympathetic resonance — cowbell shimmer on drum hits
            val resoUri = RESONATOR_URI
            put("$resoUri:${ResonatorSymbol.STRUCTURE.symbol}", PortValue.FloatValue(0.45f))
            put("$resoUri:${ResonatorSymbol.BRIGHTNESS.symbol}", PortValue.FloatValue(0.55f))
            put("$resoUri:${ResonatorSymbol.DAMPING.symbol}", PortValue.FloatValue(0.40f))
            put("$resoUri:${ResonatorSymbol.POSITION.symbol}", PortValue.FloatValue(0.30f))
            put("$resoUri:${ResonatorSymbol.MIX.symbol}", PortValue.FloatValue(0.0f))  // off, user dials in

            // ═══ DELAY ═══
            // Pink Floyd dotted-eighth — "Run Like Hell" / "Another Brick" rhythmic delay
            val delayUri = DELAY_URI
            put("$delayUri:${DelaySymbol.TIME_1.symbol}", PortValue.FloatValue(0.19f))      // dotted eighth
            put("$delayUri:${DelaySymbol.TIME_2.symbol}", PortValue.FloatValue(0.375f))     // dotted quarter
            put("$delayUri:${DelaySymbol.MOD_DEPTH_1.symbol}", PortValue.FloatValue(0.08f)) // subtle warble
            put("$delayUri:${DelaySymbol.MOD_DEPTH_2.symbol}", PortValue.FloatValue(0.15f))
            put("$delayUri:${DelaySymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.68f))    // long tail
            put("$delayUri:${DelaySymbol.MIX.symbol}", PortValue.FloatValue(0.35f))         // present but not drowning

            // ═══ REVERB ═══
            // Cavernous dark hall — "Echoes" in an empty cathedral
            val reverbUri = REVERB_URI
            put("$reverbUri:${ReverbSymbol.AMOUNT.symbol}", PortValue.FloatValue(0.72f))
            put("$reverbUri:${ReverbSymbol.TIME.symbol}", PortValue.FloatValue(0.90f))      // very long tail
            put("$reverbUri:${ReverbSymbol.DAMPING.symbol}", PortValue.FloatValue(0.70f))   // dark, rolled-off highs
            put("$reverbUri:${ReverbSymbol.DIFFUSION.symbol}", PortValue.FloatValue(0.55f)) // dense diffusion

            // ═══ DISTORTION ═══
            // Warm tube saturation — just enough to thicken, not harsh
            val distUri = DISTORTION_URI
            put("$distUri:${DistortionSymbol.DRIVE.symbol}", PortValue.FloatValue(0.35f))
            put("$distUri:${DistortionSymbol.MIX.symbol}", PortValue.FloatValue(0.25f))

            // ═══ TEMPO ═══
            // Slow Pink Floyd groove — "One of These Days" ~90 BPM
            put("org.balch.orpheus.plugins.tempo:bpm", PortValue.FloatValue(88f))

            // ═══ DRUMS (Grids pattern + synthesis) ═══
            // Slow hypnotic beat: deep 808 kick, metallic hat, snare accent
            val beatsUri = BEATS_URI
            put("$beatsUri:${BeatsSymbol.X.symbol}", PortValue.FloatValue(0.35f))
            put("$beatsUri:${BeatsSymbol.Y.symbol}", PortValue.FloatValue(0.50f))
            put("$beatsUri:${BeatsSymbol.RANDOMNESS.symbol}", PortValue.FloatValue(0.15f))
            put("$beatsUri:${BeatsSymbol.DENSITY_0.symbol}", PortValue.FloatValue(0.45f))  // kick: steady
            put("$beatsUri:${BeatsSymbol.DENSITY_1.symbol}", PortValue.FloatValue(0.25f))  // snare: sparse accent
            put("$beatsUri:${BeatsSymbol.DENSITY_2.symbol}", PortValue.FloatValue(0.55f))  // hat: ticking clock

            // ═══ STEREO PANNING ═══
            // Q0: center (bass anchored), Q1: slight spread, Q2: wide stereo, Q3: extreme spread
            val stereoUri = STEREO_URI
            val pans = listOf(
                0.0f, 0.0f,     // Q0: center bass
                -0.25f, 0.25f,  // Q1: lead spread
                -0.65f, 0.65f,  // Q2: wide textures
                -0.85f, 0.85f,  // Q3: extreme pad spread
                -0.4f, 0.4f, -0.2f, 0.2f  // REPL: moderate spread
            )
            pans.forEachIndexed { i, p -> put("$stereoUri:voice_pan_$i", PortValue.FloatValue(p)) }

            // ═══ TTS ═══
            // "One of These Days" — deep menacing voice through the void
            val ttsUri = TTS_URI
            put("$ttsUri:text_input", PortValue.StringValue(
                "One of these days I'm going to cut you a piece of ice cream cake"
            ))
            put("$ttsUri:selected_voice", PortValue.StringValue("Daniel"))
            put("$ttsUri:${TtsSymbol.RATE.symbol}", PortValue.FloatValue(0.35f))
            put("$ttsUri:${TtsSymbol.SPEED.symbol}", PortValue.FloatValue(0.25f))
            put("$ttsUri:${TtsSymbol.VOLUME.symbol}", PortValue.FloatValue(0.6f))
            put("$ttsUri:${TtsSymbol.PHASER.symbol}", PortValue.FloatValue(0.7f))
            put("$ttsUri:${TtsSymbol.FEEDBACK.symbol}", PortValue.FloatValue(0.65f))
            put("$ttsUri:${TtsSymbol.REVERB.symbol}", PortValue.FloatValue(0.8f))
        },
        createdAt = 0L
    )
}
