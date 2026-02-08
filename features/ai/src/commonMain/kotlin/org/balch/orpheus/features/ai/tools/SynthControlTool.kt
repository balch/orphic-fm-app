package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol

@Serializable
data class SynthControlArgs(
    @property:LLMDescription("""
        Control ID for the synth parameter. Valid controls include:

        GLOBAL: VIBRATO, DRIVE, DISTORTION_MIX, VOICE_COUPLING, TOTAL_FEEDBACK

        VOICES (1-12): VOICE_TUNE_1..12, VOICE_FM_DEPTH_1..12, VOICE_ENV_SPEED_1..12

        QUADS (1-3): QUAD_PITCH_1..3, QUAD_HOLD_1..3, QUAD_VOLUME_1..3

        PAIRS (1-6): DUO_MOD_SOURCE_1..6, PAIR_SHARPNESS_1..6, VOICE_ENGINE_1..6, VOICE_ENGINE_HARMONICS_1..6

        LFO: HYPER_LFO_A, HYPER_LFO_B, HYPER_LFO_MODE, HYPER_LFO_LINK

        DELAY: DELAY_TIME_1/2, DELAY_MOD_1/2, DELAY_FEEDBACK, DELAY_MIX, DELAY_MOD_SOURCE, DELAY_LFO_WAVEFORM

        RESONATOR: RESONATOR_MODE, RESONATOR_STRUCTURE, RESONATOR_BRIGHTNESS, RESONATOR_DAMPING, RESONATOR_POSITION, RESONATOR_MIX

        MATRIX (Warps Cross-Modulation):
        - MATRIX_ALGORITHM: 0.0-0.875 in 0.125 steps (Crossfade, Fold, Ring Mod, XOR, Comparator, Vocoder, Chebyshev, Freq Shift)
        - MATRIX_TIMBRE: Algorithm-specific tone (0-1)
        - MATRIX_CARRIER_LEVEL, MATRIX_MODULATOR_LEVEL: Input volumes (0-1)
        - MATRIX_CARRIER_SOURCE, MATRIX_MODULATOR_SOURCE: Audio routing (0=Synth, 0.5=Drums, 1=REPL)
        - MATRIX_MIX: Dry/wet blend (0=bypass, 1=fully processed)

        BENDER: Special pitch bend control (-1.0 to +1.0)

        VOICE ENGINE (1-6): VOICE_ENGINE_1..6
        Synthesis engine selection per duo pair. Value is an integer engine ID:
          0 = OSC (default FM oscillators — classic warm Orpheus sound)
          5 = FM Synthesis (two-operator FM — bells, keys, metallic tones, bright harmonics)
          6 = Filtered Noise (noise through resonant filter — wind, textures, sweeps, percussion)
          7 = Waveshaping (wavefolder — harsh to warm distortion, complex harmonics)
          8 = Virtual Analog (classic subtractive — sawtooth/pulse with filter)
          9 = Additive (harmonic partials — organ-like, evolving spectral tones)
          10 = Grain (granular/wavetable — textural, glitchy, evolving sounds)
          11 = String (Karplus-Strong — plucked/bowed strings, realistic decay)
          12 = Modal (physical modeling — resonant surfaces, bells, metallic percussion)
        Drum engines (use sparingly on voice pairs):
          1 = Analog Bass Drum, 2 = Analog Snare, 3 = Hi-Hat, 4 = FM Drum

        VOICE ENGINE HARMONICS (1-6): VOICE_ENGINE_HARMONICS_1..6
        Engine-specific tonal control (0.0-1.0):
          - OSC (engine 0): FM self-feedback amount (0=clean, 1=chaotic)
          - All others: Engine-specific harmonic richness parameter

        VOICE ENGINE SOUND DESIGN TIPS:
          - Use String or Modal for plucked/metallic textures
          - Use FM or Additive for evolving tonal drones
          - Use Grain for glitchy, textural layers
          - Use VA for classic analog synth sounds
          - Use Waveshaping for aggressive, distorted timbres
          - Combine different engines across pairs for rich layered sounds
          - PAIR_SHARPNESS controls timbre for the active engine
          - VOICE_FM_DEPTH controls morph parameter for Plaits engines

        Use uppercase names (e.g., MATRIX_ALGORITHM, VOICE_TUNE_1).
    """)
    val controlId: String,

    @property:LLMDescription("""
        Value for the control parameter (0.0 to 1.0, except BENDER which uses -1.0 to +1.0).

        Special cases:
        - MATRIX_ALGORITHM: 0.0=Crossfade, 0.125=Fold, 0.25=RingMod, 0.375=XOR, 0.5=Comparator, 0.625=Vocoder, 0.75=Chebyshev, 0.875=FreqShift
        - MATRIX_*_SOURCE: 0=Synth, 0.5=Drums, 1=REPL patterns
        - DUO_MOD_SOURCE: 0=VoiceFM, 0.5=Off, 1=LFO
        - HYPER_LFO_MODE: 0=AND, 0.5=OFF, 1=OR
        - RESONATOR_MODE: 0=Modal (bell), 0.5=String, 1=Sympathetic (sitar)
        - BENDER: -1=full down, 0=center, +1=full up
        - VOICE_ENGINE: Integer engine ID (0, 5-12 for pitched; 1-4 for drums). NOT 0-1 range.
    """)
    val value: Float
)

@Serializable
data class SynthControlResult(val success: Boolean, val message: String)

/**
 * Tool for controlling synth parameters via the SynthController.
 * Uses linear ramping for smooth transitions when AI changes values.
 *
 * ## Tuning Voices to Musical Notes
 *
 * VOICE_TUNE_1 through VOICE_TUNE_12 control voice pitch using a 0.0-1.0 range.
 *
 * **Base Frequency Formula:**
 * `frequency = 55Hz × 2^(tune × 4)`
 *
 * **Key Values:**
 * - 0.0 = A1 (55 Hz)
 * - 0.5 = A3 (220 Hz) ← "Unity" point
 * - 1.0 = A5 (880 Hz)
 *
 * **Semitone Calculation:**
 * To tune to a specific note relative to A3 (at tune=0.5):
 * `tuneValue = 0.5 + (semitones / 48.0)`
 *
 * **Common Musical Notes (relative to A3):**
 * - A3 (unity) = 0.500
 * - B3 (+2 semi) = 0.542
 * - C4 (+3 semi) = 0.562
 * - D4 (+5 semi) = 0.604
 * - E4 (+7 semi) = 0.646
 * - F4 (+8 semi) = 0.667
 * - G4 (+10 semi) = 0.708
 * - A4 (+12 semi) = 0.750
 * - C5 (+15 semi) = 0.812
 *
 * **Voice Pitch Multipliers:**
 * Each voice has a built-in pitch multiplier:
 * - Voices 1-2: 0.5× (one octave lower)
 * - Voices 3-6: 1.0× (as calculated)
 * - Voices 7-8: 2.0× (one octave higher)
 * - Voices 9-12: 1.0× (as calculated)
 *
 * So voices 7-8 at tune=0.5 play A4 (440Hz, concert pitch).
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class SynthControlTool @Inject constructor(
    private val synthController: SynthController
) : Tool<SynthControlArgs, SynthControlResult>(
    argsSerializer = SynthControlArgs.serializer(),
    resultSerializer = SynthControlResult.serializer(),
    name = "synth_control",
    description = """
        Control synthesizer parameters like volume, vibrato, distortion, delay, pan, quad settings, duo mod sources, voice engine selection, and the pitch bender.
        Use this to adjust the sound based on user requests.
        Values should be between 0.0 and 1.0 (except BENDER which uses -1.0 to +1.0).
        For DUO_MOD_SOURCE: 0.0=VoiceFM, 0.5=Off, 1.0=LFO.

        VOICE TUNING TO MUSICAL NOTES:
        VOICE_TUNE uses 0.0-1.0 where 0.5 = A3 (220Hz).
        To tune to other notes: tuneValue = 0.5 + (semitones from A3 / 48.0)
        Common notes: C4=0.562, D4=0.604, E4=0.646, F4=0.667, G4=0.708, A4=0.750
        Note: Voices 7-8 have 2x pitch multiplier, so at 0.5 they play A4 (440Hz concert pitch).

        BENDER SPECIAL CONTROL:
        The BENDER creates expressive pitch glides with a spring-loaded feel. Perfect for:
        - Whale song effects: Slow sweeps from 0.0 to ±0.3 with gradual return to center
        - Dolphin clicks: Quick, short bends (0.0 → 0.5 → 0.0 rapidly)
        - Sirens: Oscillate between -0.5 and +0.5 at varying speeds
        - Tension/release: Pull to extreme (+1.0 or -1.0), hold, then release to 0.0 for spring sound
        Use BENDER when you want to create organic, gliding pitch movements.

        RESONATOR (Rings Physical Modeling):
        Physical modeling resonator ported from Mutable Instruments Rings.
        Perfect for adding metallic, string-like, or bell tones to your sound:
        - RESONATOR_MODE: 0=Modal (bell/plate), 0.5=String (Karplus-Strong), 1=Sympathetic (sitar-like)
        - RESONATOR_STRUCTURE: Controls harmonic spread/inharmonicity (0-1)
        - RESONATOR_BRIGHTNESS: High frequency content (0=dark, 1=bright)
        - RESONATOR_DAMPING: Decay time (0=long sustain, 1=quick decay)
        - RESONATOR_POSITION: Excitation point (0=edge, 0.5=center, 1=opposite edge)
        - RESONATOR_MIX: Dry/wet blend (0=dry/off, 1=fully resonated)

        RESONATOR SOUND DESIGN TIPS:
        - Modal mode: Bell-like tones, struck metal/glass character
        - String mode: Plucked guitar/harp sounds with realistic decay
        - Sympathetic: Sitar-like with drone strings that ring in sympathy
        - Low structure + high brightness = shimmering, crystalline
        - High structure + low brightness = deep, gong-like rumble

        ⚠️ RESONATOR MODE CHANGE PROCEDURE:
        1. Lower RESONATOR_MIX to 0.1 first (avoids jarring transition)
        2. Change RESONATOR_MODE
        3. Slowly ramp RESONATOR_MIX back to desired level
        Avoid sustained high brightness + high structure (causes listener fatigue).

        MATRIX (Meta-Modulator):
        Signal routing and cross-modulation matrix ported from Mutable Instruments Warps (Parasites firmware).
        Cross-modulates carrier and modulator signals using 8 different algorithms:
        - MATRIX_ALGORITHM: Selects algorithm (0.0-0.875 in steps of 0.125):
          * 0.000-0.124: Crossfade - Simple crossfading between inputs
          * 0.125-0.249: Cross-folding - Wave folding modulation
          * 0.250-0.374: Diode ring modulator - Classic harsh metallic RM
          * 0.375-0.499: XOR (digital destroyer) - Bitwise chaos
          * 0.500-0.624: Comparator - Gate/trigger generation
          * 0.625-0.749: Vocoder - Spectral transfer
          * 0.750-0.874: Chebyshev waveshaping
          * 0.875-1.000: Frequency shifter - Inharmonic shifting
        - MATRIX_TIMBRE: Algorithm-specific timbral control (0-1)
        - MATRIX_CARRIER_LEVEL: Carrier input level (0-1)
        - MATRIX_MODULATOR_LEVEL: Modulator input level (0-1)
        - MATRIX_CARRIER_SOURCE: Carrier audio source (0=Synth, 0.5=Drums, 1=REPL)
        - MATRIX_MODULATOR_SOURCE: Modulator audio source (0=Synth, 0.5=Drums, 1=REPL)
        - MATRIX_MIX: Dry/wet blend (0=dry/bypass, 1=fully processed)

        MATRIX SOUND DESIGN TIPS:
        - Crossfade: Use for smooth transitions between sources
        - Cross-folding: Creates rich harmonics, great for aggressive sounds
        - Ring mod: Classic metallic tones, increase timbre for more chaos
        - XOR: Digital destruction, glitchy artifacts
        - Comparator: Rhythmic gate patterns from audio
        - Vocoder: Synth talks like drums, or vice versa
        - Chebyshev: Smooth waveshaping, controlled distortion
        - Freq shifter: Inharmonic shifts, alien tones (timbre = shift amount)
    """.trimIndent()
) {
    // Track current values for each control to enable smooth ramping
    private val currentValues = mutableMapOf<PluginControlId, Float>()

    // Ramp configuration: 500ms over ~25 steps = 20ms per step
    private val rampDurationMs = 500L
    private val rampSteps = 25

    /**
     * Resolve a friendly AI control name to its PortSymbol.
     * Returns null for special controls (BENDER) or unknown IDs.
     */
    private fun resolvePortSymbol(id: String): PortSymbol? = when (id) {
        // Global
        "VIBRATO" -> VoiceSymbol.VIBRATO
        "DRIVE" -> DistortionSymbol.DRIVE
        "DISTORTION_MIX" -> DistortionSymbol.MIX
        "VOICE_COUPLING" -> VoiceSymbol.COUPLING
        "TOTAL_FEEDBACK" -> VoiceSymbol.TOTAL_FEEDBACK

        // Quad controls (1-indexed AI → 0-indexed symbol)
        "QUAD_PITCH_1" -> VoiceSymbol.quadPitch(0)
        "QUAD_PITCH_2" -> VoiceSymbol.quadPitch(1)
        "QUAD_PITCH_3" -> VoiceSymbol.quadPitch(2)
        "QUAD_HOLD_1" -> VoiceSymbol.quadHold(0)
        "QUAD_HOLD_2" -> VoiceSymbol.quadHold(1)
        "QUAD_HOLD_3" -> VoiceSymbol.quadHold(2)
        "QUAD_VOLUME_1" -> VoiceSymbol.quadVolume(0)
        "QUAD_VOLUME_2" -> VoiceSymbol.quadVolume(1)
        "QUAD_VOLUME_3" -> VoiceSymbol.quadVolume(2)

        // Duo mod sources (1-indexed → 0-indexed)
        "DUO_MOD_SOURCE_1" -> VoiceSymbol.duoModSource(0)
        "DUO_MOD_SOURCE_2" -> VoiceSymbol.duoModSource(1)
        "DUO_MOD_SOURCE_3" -> VoiceSymbol.duoModSource(2)
        "DUO_MOD_SOURCE_4" -> VoiceSymbol.duoModSource(3)
        "DUO_MOD_SOURCE_5" -> VoiceSymbol.duoModSource(4)
        "DUO_MOD_SOURCE_6" -> VoiceSymbol.duoModSource(5)

        // Pair sharpness (1-indexed → 0-indexed)
        "PAIR_SHARPNESS_1" -> VoiceSymbol.pairSharpness(0)
        "PAIR_SHARPNESS_2" -> VoiceSymbol.pairSharpness(1)
        "PAIR_SHARPNESS_3" -> VoiceSymbol.pairSharpness(2)
        "PAIR_SHARPNESS_4" -> VoiceSymbol.pairSharpness(3)
        "PAIR_SHARPNESS_5" -> VoiceSymbol.pairSharpness(4)
        "PAIR_SHARPNESS_6" -> VoiceSymbol.pairSharpness(5)

        // Voice engine selection (1-indexed → 0-indexed)
        "VOICE_ENGINE_1" -> VoiceSymbol.pairEngine(0)
        "VOICE_ENGINE_2" -> VoiceSymbol.pairEngine(1)
        "VOICE_ENGINE_3" -> VoiceSymbol.pairEngine(2)
        "VOICE_ENGINE_4" -> VoiceSymbol.pairEngine(3)
        "VOICE_ENGINE_5" -> VoiceSymbol.pairEngine(4)
        "VOICE_ENGINE_6" -> VoiceSymbol.pairEngine(5)

        // Voice engine harmonics (1-indexed → 0-indexed)
        "VOICE_ENGINE_HARMONICS_1" -> VoiceSymbol.pairHarmonics(0)
        "VOICE_ENGINE_HARMONICS_2" -> VoiceSymbol.pairHarmonics(1)
        "VOICE_ENGINE_HARMONICS_3" -> VoiceSymbol.pairHarmonics(2)
        "VOICE_ENGINE_HARMONICS_4" -> VoiceSymbol.pairHarmonics(3)
        "VOICE_ENGINE_HARMONICS_5" -> VoiceSymbol.pairHarmonics(4)
        "VOICE_ENGINE_HARMONICS_6" -> VoiceSymbol.pairHarmonics(5)

        // LFO controls
        "HYPER_LFO_A", "LFO_A" -> DuoLfoSymbol.FREQ_A
        "HYPER_LFO_B", "LFO_B" -> DuoLfoSymbol.FREQ_B
        "HYPER_LFO_MODE" -> DuoLfoSymbol.MODE
        "HYPER_LFO_LINK" -> DuoLfoSymbol.LINK

        // Delay controls
        "DELAY_TIME_1" -> DelaySymbol.TIME_1
        "DELAY_TIME_2" -> DelaySymbol.TIME_2
        "DELAY_MOD_1" -> DelaySymbol.MOD_DEPTH_1
        "DELAY_MOD_2" -> DelaySymbol.MOD_DEPTH_2
        "DELAY_FEEDBACK" -> DelaySymbol.FEEDBACK
        "DELAY_MIX" -> DelaySymbol.MIX
        "DELAY_MOD_SOURCE" -> DelaySymbol.MOD_SOURCE
        "DELAY_LFO_WAVEFORM" -> DelaySymbol.LFO_WAVEFORM

        // Resonator (Rings) controls
        "RESONATOR_MODE" -> ResonatorSymbol.MODE
        "RESONATOR_STRUCTURE" -> ResonatorSymbol.STRUCTURE
        "RESONATOR_BRIGHTNESS" -> ResonatorSymbol.BRIGHTNESS
        "RESONATOR_DAMPING" -> ResonatorSymbol.DAMPING
        "RESONATOR_POSITION" -> ResonatorSymbol.POSITION
        "RESONATOR_MIX" -> ResonatorSymbol.MIX

        // Warps (Meta-Modulator) controls — aliased as MATRIX for AI
        "MATRIX_ALGORITHM", "WARPS_ALGORITHM" -> WarpsSymbol.ALGORITHM
        "MATRIX_TIMBRE", "WARPS_TIMBRE" -> WarpsSymbol.TIMBRE
        "MATRIX_CARRIER_LEVEL", "WARPS_CARRIER_LEVEL" -> WarpsSymbol.LEVEL1
        "MATRIX_MODULATOR_LEVEL", "WARPS_MODULATOR_LEVEL" -> WarpsSymbol.LEVEL2
        "MATRIX_CARRIER_SOURCE", "WARPS_CARRIER_SOURCE" -> WarpsSymbol.CARRIER_SOURCE
        "MATRIX_MODULATOR_SOURCE", "WARPS_MODULATOR_SOURCE" -> WarpsSymbol.MODULATOR_SOURCE
        "MATRIX_MIX", "WARPS_MIX" -> WarpsSymbol.MIX

        // Per-voice controls (VOICE_TUNE_1 through VOICE_TUNE_12, etc.)
        else -> {
            val voiceTuneMatch = Regex("VOICE_TUNE_(\\d+)").find(id)
            val voiceFmMatch = Regex("VOICE_FM_DEPTH_(\\d+)").find(id)
            val voiceEnvMatch = Regex("VOICE_ENV_SPEED_(\\d+)").find(id)

            when {
                voiceTuneMatch != null -> {
                    val idx = voiceTuneMatch.groupValues[1].toInt() - 1
                    VoiceSymbol.tune(idx)
                }
                voiceFmMatch != null -> {
                    val idx = voiceFmMatch.groupValues[1].toInt() - 1
                    VoiceSymbol.modDepth(idx)
                }
                voiceEnvMatch != null -> {
                    val idx = voiceEnvMatch.groupValues[1].toInt() - 1
                    VoiceSymbol.envSpeed(idx)
                }
                else -> null
            }
        }
    }

    override suspend fun execute(args: SynthControlArgs): SynthControlResult {
        // Special handling for BENDER - uses -1 to +1 range
        if (args.controlId.uppercase() == "BENDER") {
            return executeBend(args.value)
        }

        val normalizedValue = args.value.coerceIn(0f, 1f)

        val portSymbol = resolvePortSymbol(args.controlId.uppercase())
            ?: return SynthControlResult(
                success = false,
                message = "Unknown control: ${args.controlId}"
            )

        val targetId = portSymbol.controlId

        // Voice engine selection — set immediately as integer, no ramping
        val upperControlId = args.controlId.uppercase()
        if (upperControlId.startsWith("VOICE_ENGINE_") &&
            !upperControlId.contains("HARMONICS")) {
            val intValue = args.value.toInt()
            synthController.setPluginControl(
                id = targetId,
                value = PortValue.IntValue(intValue),
                origin = ControlEventOrigin.AI
            )
            return SynthControlResult(success = true, message = "Set ${portSymbol.displayName} to engine $intValue")
        }

        // Get current value (or use 0.5 as default starting point)
        val startValue = currentValues[targetId] ?: 0.5f
        val stepDelayMs = rampDurationMs / rampSteps

        // Linear ramp from current to target
        for (step in 1..rampSteps) {
            val t = step.toFloat() / rampSteps
            val interpolatedValue = startValue + (normalizedValue - startValue) * t

            synthController.setPluginControl(
                id = targetId,
                value = PortValue.FloatValue(interpolatedValue),
                origin = ControlEventOrigin.AI
            )

            if (step < rampSteps) {
                delay(stepDelayMs)
            }
        }

        // Update tracked value
        currentValues[targetId] = normalizedValue

        return SynthControlResult(
            success = true,
            message = "Ramped ${portSymbol.displayName} to ${(normalizedValue * 100).toInt()}%"
        )
    }

    /**
     * Special handling for BENDER control.
     * Uses -1 to +1 range and the dedicated emitBendChange method.
     * Creates smooth pitch glides for expressive effects like whale songs.
     */
    private suspend fun executeBend(targetValue: Float): SynthControlResult {
        val normalizedValue = targetValue.coerceIn(-1f, 1f)

        // Get current bend value (or use 0f as default starting point - center)
        val startValue = currentValues[BENDER_KEY] ?: 0f
        val stepDelayMs = rampDurationMs / rampSteps

        // Smooth ramp from current to target
        for (step in 1..rampSteps) {
            val t = step.toFloat() / rampSteps
            val interpolatedValue = startValue + (normalizedValue - startValue) * t

            synthController.emitBendChange(interpolatedValue)

            if (step < rampSteps) {
                delay(stepDelayMs)
            }
        }

        // Update tracked value
        currentValues[BENDER_KEY] = normalizedValue

        // Format the message based on bend direction
        val direction = when {
            normalizedValue > 0.1f -> "up"
            normalizedValue < -0.1f -> "down"
            else -> "center"
        }

        return SynthControlResult(
            success = true,
            message = "Bent pitch $direction to ${(normalizedValue * 100).toInt()}%"
        )
    }

    companion object {
        /** Synthetic key for the bender (not a real plugin port) */
        private val BENDER_KEY = PluginControlId("bender", "bend")
    }
}
