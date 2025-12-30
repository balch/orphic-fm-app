package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController

/**
 * Tool for controlling synth parameters via the SynthController.
 * Uses linear ramping for smooth transitions when AI changes values.
 */
@SingleIn(AppScope::class)
class SynthControlTool @Inject constructor(
    private val synthController: SynthController
) : Tool<SynthControlTool.Args, SynthControlTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "synth_control",
    description = """
        Control synthesizer parameters like volume, vibrato, distortion, delay, pan, quad settings, and duo mod sources.
        Use this to adjust the sound based on user requests.
        Values should be between 0.0 and 1.0. 
        For DUO_MOD_SOURCE: 0.0=VoiceFM, 0.5=Off, 1.0=LFO.
    """.trimIndent()
) {
    // Track current values for each control to enable smooth ramping
    private val currentValues = mutableMapOf<String, Float>()
    
    // Ramp configuration: 500ms over ~25 steps = 20ms per step
    private val rampDurationMs = 500L
    private val rampSteps = 25
    @Serializable
    data class Args(
        @property:LLMDescription("""
            Control ID. Available controls:
            
            GLOBAL:
            - VIBRATO: LFO modulation depth
            - DRIVE: Saturation/warmth
            - DISTORTION_MIX: Distortion wet/dry
            - VOICE_COUPLING: FM coupling between voices
            - TOTAL_FEEDBACK: Global feedback amount
            
            LFO:
            - HYPER_LFO_A: LFO A speed (0.0-1.0)
            - HYPER_LFO_B: LFO B speed (0.0-1.0)
            - HYPER_LFO_MODE: LFO combine mode (0.0=AND, 0.5=OFF, 1.0=OR)
            - HYPER_LFO_LINK: Link LFOs (0=independent, 1=linked)
            
            DELAY:
            - DELAY_TIME_1: First delay time
            - DELAY_TIME_2: Second delay time
            - DELAY_MOD_1: Delay 1 modulation depth
            - DELAY_MOD_2: Delay 2 modulation depth
            - DELAY_FEEDBACK: Echo repeats
            - DELAY_MIX: Delay wet/dry
            - DELAY_MOD_SOURCE: Mod source (0=self, 1=LFO)
            - DELAY_LFO_WAVEFORM: LFO shape (0=triangle, 1=square)
            
            VOICES (1-8):
            - VOICE_TUNE_1 through VOICE_TUNE_8: Voice pitch (0.5=unity)
            - VOICE_FM_DEPTH_1 through VOICE_FM_DEPTH_8: FM modulation depth
            - VOICE_ENV_SPEED_1 through VOICE_ENV_SPEED_8: Envelope speed (0=fast, 1=slow)
            
            QUADS:
            - QUAD_PITCH_1: Pitch for Quad 1 (Voices 1-4)
            - QUAD_PITCH_2: Pitch for Quad 2 (Voices 5-8)
            - QUAD_PITCH_3: Pitch for Quad 3 (Voices 9-12)
            - QUAD_HOLD_1, QUAD_HOLD_2, QUAD_HOLD_3: Hold/Drone level for quad groups
            - QUAD_VOLUME_3: Volume for Quad 3 (drone voices 9-12)
            
            PAIRS/DUOS (1-4):
            - DUO_MOD_SOURCE_1..4: Modulation source (0=VoiceFM, 0.5=Off, 1=LFO)
            - PAIR_SHARPNESS_1..4: Waveform sharpness (0=triangle, 1=square)
        """)
        val controlId: String,

        @property:LLMDescription("Value to set (0.0 to 1.0)")
        val value: Float
    )

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String
    )

    override suspend fun execute(args: Args): Result {
        val normalizedValue = args.value.coerceIn(0f, 1f)
        
        // Map friendly aliases to actual system IDs (which are lowercase)
        val targetId = when (val id = args.controlId.uppercase()) {
            // Quad controls
            "QUAD_PITCH_1" -> "quad_0_pitch"
            "QUAD_PITCH_2" -> "quad_1_pitch"
            "QUAD_HOLD_1" -> "quad_0_hold"
            "QUAD_HOLD_2" -> "quad_1_hold"
            "QUAD_PITCH_3" -> "quad_2_pitch"
            "QUAD_HOLD_3" -> "quad_2_hold"
            "QUAD_VOLUME_3" -> "quad_2_volume"
            
            // Duo mod sources
            "DUO_MOD_SOURCE_1" -> "pair_0_mod_source"
            "DUO_MOD_SOURCE_2" -> "pair_1_mod_source"
            "DUO_MOD_SOURCE_3" -> "pair_2_mod_source"
            "DUO_MOD_SOURCE_4" -> "pair_3_mod_source"
            "DUO_MOD_SOURCE_5", "DUO_MOD_SOURCE_9", "DUO_MOD_SOURCE_10" -> "pair_4_mod_source"
            "DUO_MOD_SOURCE_6", "DUO_MOD_SOURCE_11", "DUO_MOD_SOURCE_12" -> "pair_5_mod_source"
            
            // Pair sharpness
            "PAIR_SHARPNESS_1" -> "pair_0_sharpness"
            "PAIR_SHARPNESS_2" -> "pair_1_sharpness"
            "PAIR_SHARPNESS_3" -> "pair_2_sharpness"
            "PAIR_SHARPNESS_4" -> "pair_3_sharpness"
            
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
            
            // Per-voice controls (VOICE_TUNE_1 through VOICE_TUNE_8, etc.)
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
        
        return Result(
            success = true,
            message = "Ramped $targetId to ${(normalizedValue * 100).toInt()}%"
        )
    }
}
