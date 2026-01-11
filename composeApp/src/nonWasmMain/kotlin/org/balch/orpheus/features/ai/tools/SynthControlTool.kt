package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController

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
actual class SynthControlTool @Inject constructor(
    private val synthController: SynthController
) : Tool<SynthControlArgs, SynthControlResult>(
    argsSerializer = SynthControlArgs.serializer(),
    resultSerializer = SynthControlResult.serializer(),
    name = "synth_control",
    description = """
        Control synthesizer parameters like volume, vibrato, distortion, delay, pan, quad settings, duo mod sources, and the pitch bender.
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
        - RESONATOR_ENABLED: 0=off, 1=on
        - RESONATOR_MODE: 0=Modal (bell/plate), 0.5=String (Karplus-Strong), 1=Sympathetic (sitar-like)
        - RESONATOR_STRUCTURE: Controls harmonic spread/inharmonicity (0-1)
        - RESONATOR_BRIGHTNESS: High frequency content (0=dark, 1=bright)
        - RESONATOR_DAMPING: Decay time (0=long sustain, 1=quick decay)
        - RESONATOR_POSITION: Excitation point (0=edge, 0.5=center, 1=opposite edge)
        - RESONATOR_MIX: Dry/wet blend (0=dry, 1=fully resonated)
        
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
    """.trimIndent()
) {
    // Track current values for each control to enable smooth ramping
    private val currentValues = mutableMapOf<String, Float>()
    
    // Ramp configuration: 500ms over ~25 steps = 20ms per step
    private val rampDurationMs = 500L
    private val rampSteps = 25

    override actual suspend fun execute(args: SynthControlArgs): SynthControlResult {
        // Special handling for BENDER - uses -1 to +1 range
        if (args.controlId.uppercase() == "BENDER") {
            return executeBend(args.value)
        }
        
        val normalizedValue = args.value.coerceIn(0f, 1f)
        
        // Map friendly aliases to actual system IDs (which are lowercase)
        val targetId = when (val id = args.controlId.uppercase()) {
            // Quad controls
            "QUAD_PITCH_1" -> "quad_0_pitch"
            "QUAD_PITCH_2" -> "quad_1_pitch"
            "QUAD_PITCH_3" -> "quad_2_pitch"
            "QUAD_HOLD_1" -> "quad_0_hold"
            "QUAD_HOLD_2" -> "quad_1_hold"
            "QUAD_HOLD_3" -> "quad_2_hold"

            "QUAD_VOLUME_1" -> "quad_0_volume"
            "QUAD_VOLUME_2" -> "quad_1_volume"
            "QUAD_VOLUME_3" -> "quad_2_volume"
            
            // Duo mod sources
            "DUO_MOD_SOURCE_1" -> "pair_0_mod_source"
            "DUO_MOD_SOURCE_2" -> "pair_1_mod_source"
            "DUO_MOD_SOURCE_3" -> "pair_2_mod_source"
            "DUO_MOD_SOURCE_4" -> "pair_3_mod_source"
            "DUO_MOD_SOURCE_5" -> "pair_4_mod_source"
            "DUO_MOD_SOURCE_6" -> "pair_5_mod_source"
            
            // Pair sharpness
            "PAIR_SHARPNESS_1" -> "pair_0_sharpness"
            "PAIR_SHARPNESS_2" -> "pair_1_sharpness"
            "PAIR_SHARPNESS_3" -> "pair_2_sharpness"
            "PAIR_SHARPNESS_4" -> "pair_3_sharpness"
            "PAIR_SHARPNESS_5" -> "pair_4_sharpness"
            "PAIR_SHARPNESS_6" -> "pair_5_sharpness"

            // LFO controls - use ControlIds constants
            "HYPER_LFO_A", "LFO_A" -> "hyper_lfo_a"
            "HYPER_LFO_B", "LFO_B" -> "hyper_lfo_b"
            "HYPER_LFO_MODE" -> "hyper_lfo_mode"
            "HYPER_LFO_LINK" -> "hyper_lfo_link"
            
            // Delay controls - use ControlIds constants
            "DELAY_TIME_1" -> "delay_time_1"
            "DELAY_TIME_2" -> "delay_time_2"
            "DELAY_MOD_1" -> "delay_mod_1"
            "DELAY_MOD_2" -> "delay_mod_2"
            "DELAY_MOD_SOURCE" -> "delay_mod_source"
            "DELAY_LFO_WAVEFORM" -> "delay_lfo_waveform"
            
            // Resonator (Rings) controls
            "RESONATOR_ENABLED" -> "resonator_enabled"
            "RESONATOR_MODE" -> "resonator_mode"
            "RESONATOR_STRUCTURE" -> "resonator_structure"
            "RESONATOR_BRIGHTNESS" -> "resonator_brightness"
            "RESONATOR_DAMPING" -> "resonator_damping"
            "RESONATOR_POSITION" -> "resonator_position"
            "RESONATOR_MIX" -> "resonator_mix"
            
            // Per-voice controls (VOICE_TUNE_1 through VOICE_TUNE_12, etc.)
            else -> {
                val voiceTuneMatch = Regex("VOICE_TUNE_(\\d+)").find(id)
                val voiceFmMatch = Regex("VOICE_FM_DEPTH_(\\d+)").find(id)
                val voiceEnvMatch = Regex("VOICE_ENV_SPEED_(\\d+)").find(id)
                
                when {
                    voiceTuneMatch != null -> {
                        val idx = voiceTuneMatch.groupValues[1].toInt() - 1 // Convert 1-based to 0-based
                        "voice_${idx}_tune"
                    }
                    voiceFmMatch != null -> {
                        val idx = voiceFmMatch.groupValues[1].toInt() - 1 // Convert 1-based to 0-based
                        "voice_${idx}_fm_depth"
                    }
                    voiceEnvMatch != null -> {
                        val idx = voiceEnvMatch.groupValues[1].toInt() - 1 // Convert 1-based to 0-based
                        "voice_${idx}_env_speed"
                    }
                    else -> args.controlId.lowercase()
                }
            }
        }

        // Get current value (or use 0.5 as default starting point)
        val startValue = currentValues[targetId] ?: 0.5f
        val stepDelayMs = rampDurationMs / rampSteps
        
        // Linear ramp from current to target
        for (step in 1..rampSteps) {
            val t = step.toFloat() / rampSteps
            val interpolatedValue = startValue + (normalizedValue - startValue) * t
            
            synthController.emitControlChange(
                controlId = targetId,
                value = interpolatedValue,
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
            message = "Ramped $targetId to ${(normalizedValue * 100).toInt()}%"
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
        val startValue = currentValues["bender"] ?: 0f
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
        currentValues["bender"] = normalizedValue
        
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
}
