package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController

@Serializable
data class DrumsControlArgs(
    @property:LLMDescription("""
        Control ID for the drum parameter to adjust. Valid controls include:
        
        808 DRUMS (Analog synthesis):
        - DRUM_BD_FREQ, DRUM_BD_TONE, DRUM_BD_DECAY, DRUM_BD_AFM, DRUM_BD_SFM, DRUM_BD_TRIGGER
        - DRUM_SD_FREQ, DRUM_SD_TONE, DRUM_SD_DECAY, DRUM_SD_SNAPPY, DRUM_SD_TRIGGER
        - DRUM_HH_FREQ, DRUM_HH_TONE, DRUM_HH_DECAY, DRUM_HH_NOISY, DRUM_HH_TRIGGER
        
        DRUM BEATS (Pattern sequencer):
        - BEATS_RUN (start/stop), BEATS_BPM (tempo), BEATS_X, BEATS_Y (pattern morph)
        - BEATS_DENSITY_1 (kick), BEATS_DENSITY_2 (snare), BEATS_DENSITY_3 (hi-hat)
        - BEATS_MODE (0=DRUMS, 1=EUCLIDEAN)
        - BEATS_EUCLIDEAN_LENGTH_1/2/3 (pattern lengths)
        - BEATS_SWING, BEATS_RANDOMNESS, BEATS_MIX
        
        Use uppercase names (e.g., DRUM_BD_FREQ, BEATS_RUN).
    """)
    val controlId: String,
    
    @property:LLMDescription("""
        Value for the control parameter (0.0 to 1.0).
        
        Special cases:
        - BEATS_RUN: 1.0 to start sequencer, 0.0 to stop
        - BEATS_BPM: Maps 0.0-1.0 to 60-200 BPM (0.5 ≈ 130 BPM)
        - BEATS_MODE: 0.0 for DRUMS mode, 1.0 for EUCLIDEAN mode
        - BEATS_EUCLIDEAN_LENGTH: Internally scaled to 1-32 steps
        - DRUM triggers: Set to 1.0 to trigger drum hit, 0.0 to release
        
        Typical ranges:
        - FREQ controls: 0.2-0.4 (kick), 0.4-0.6 (snare), 0.6-0.8 (hi-hat)
        - DENSITY controls: 0.0 (sparse) to 1.0 (dense), typical 0.3-0.6
        - SWING: 0.3-0.5 for classic groove
        - RANDOMNESS: 0.1-0.3 for subtle variation
    """)
    val value: Float
)

@Serializable
data class DrumsControlResult(val success: Boolean, val message: String)

/**
 * Tool for controlling drum sounds and drum beat patterns via the SynthController.
 * Uses linear ramping for smooth transitions when AI changes values.
 * 
 * ## Two Drum Systems:
 * 
 * ### 808 DRUMS (Analog-style drum synthesis)
 * Individual 808-style drum synth voices with manual triggers:
 * 
 * **Bass Drum (BD):**
 * - DRUM_BD_FREQ: Base frequency (0.0-1.0, lower = deeper)
 * - DRUM_BD_TONE: Tonal character (0.0=pure sine, 1.0=harmonics)
 * - DRUM_BD_DECAY: Decay time (0.0=short/punchy, 1.0=long/sustained)
 * - DRUM_BD_AFM: Attack FM amount (0.0-1.0, adds "thump")
 * - DRUM_BD_SFM: Self-FM amount (0.0-1.0, adds grit/distortion)
 * - DRUM_BD_TRIGGER: Trigger the bass drum (0.0=off, 1.0=trigger)
 * 
 * **Snare Drum (SD):**
 * - DRUM_SD_FREQ: Base frequency (0.0-1.0)
 * - DRUM_SD_TONE: Tonal vs noisy (0.0=tonal, 1.0=noisy)
 * - DRUM_SD_DECAY: Decay time (0.0=short/tight, 1.0=long/loose)
 * - DRUM_SD_SNAPPY: Snappiness/attack (0.0=soft, 1.0=crisp)
 * - DRUM_SD_TRIGGER: Trigger the snare drum (0.0=off, 1.0=trigger)
 * 
 * **Hi-Hat (HH):**
 * - DRUM_HH_FREQ: Base frequency (0.0-1.0)
 * - DRUM_HH_TONE: Brightness (0.0=dark/closed, 1.0=bright/open)
 * - DRUM_HH_DECAY: Decay time (0.0=short/closed, 1.0=long/open)
 * - DRUM_HH_NOISY: Noisiness (0.0=clean, 1.0=trashy)
 * - DRUM_HH_TRIGGER: Trigger the hi-hat (0.0=off, 1.0=trigger)
 * 
 * ### DRUM BEATS (Pattern Generator/Sequencer)
 * Algorithmic drum pattern generator with Euclidean rhythms:
 * 
 * **Pattern Controls:**
 * - BEATS_X: X-axis position in pattern space (0.0-1.0)
 * - BEATS_Y: Y-axis position in pattern space (0.0-1.0)
 * - BEATS_DENSITY_1: Kick drum density (0.0=sparse, 1.0=dense)
 * - BEATS_DENSITY_2: Snare density (0.0=sparse, 1.0=dense)
 * - BEATS_DENSITY_3: Hi-hat density (0.0=sparse, 1.0=dense)
 * - BEATS_RUN: Start/stop sequencer (0.0=stop, 1.0=run)
 * - BEATS_BPM: Tempo (60-200 BPM, normalized to 0.0-1.0)
 * - BEATS_MODE: Pattern mode (0.0=DRUMS mode, 1.0=EUCLIDEAN mode)
 * 
 * **Euclidean Mode Controls:**
 * - BEATS_EUCLIDEAN_LENGTH_1: Kick pattern length (1-32 steps)
 * - BEATS_EUCLIDEAN_LENGTH_2: Snare pattern length (1-32 steps)
 * - BEATS_EUCLIDEAN_LENGTH_3: Hi-hat pattern length (1-32 steps)
 * 
 * **Feel Controls:**
 * - BEATS_RANDOMNESS: Pattern randomness/variation (0.0=locked, 1.0=chaotic)
 * - BEATS_SWING: Groove swing amount (0.0=straight, 1.0=heavy swing)
 * - BEATS_MIX: Dry/wet blend (0.0=off, 1.0=full pattern)
 * 
 * ## Pattern Mode Details:
 * 
 * **DRUMS Mode (BEATS_MODE=0.0):**
 * Uses X/Y position to morph between different drum patterns.
 * Move X and Y to explore different rhythmic spaces.
 * Densities control how active each drum voice is.
 * 
 * **EUCLIDEAN Mode (BEATS_MODE=1.0):**
 * Uses Euclidean rhythm algorithms - mathematically perfect beat distributions.
 * Each drum has its own pattern length (EUCLIDEAN_LENGTH) and density.
 * Creates polyrhythmic patterns when lengths differ.
 * Example: DENSITY=0.25 with LENGTH=16 places 4 hits evenly across 16 steps.
 * 
 * ## Sound Design Tips:
 * 
 * **CREATING BEATS:**
 * 1. Set BEATS_BPM to desired tempo (0.5 = 130 BPM)
 * 2. Set BEATS_RUN to 1.0 to start
 * 3. Adjust BEATS_DENSITY controls to set how busy each drum is
 * 4. Move BEATS_X and BEATS_Y to find interesting patterns
 * 5. Add BEATS_SWING for groove (try 0.3-0.5 for classic swing)
 * 6. Add BEATS_RANDOMNESS for variation (0.1-0.3 for subtle humanization)
 * 
 * **TUNING 808 DRUMS:**
 * - Kick: Low FREQ (0.2-0.4), medium TONE (0.3-0.5), adjust DECAY for punch
 * - Snare: Medium FREQ (0.4-0.6), high SNAPPY (0.6-0.8) for crack
 * - Hi-hat: High FREQ (0.6-0.8), vary TONE for open/closed sound
 * 
 * **EUCLIDEAN RHYTHM EXAMPLES:**
 * - TR-909 pattern: Kick len=16 density=4, Snare len=16 density=4, HH len=16 density=8
 * - Polyrhythm: Kick len=16, Snare len=12, HH len=9 (creates evolving pattern)
 * - Odd-time: Kick len=13 density=5, Snare len=13 density=3 creates 13/8 feel
 * 
 * ⚠️ BEAT SEQUENCER WORKFLOW:
 * 1. Start with simple patterns - set all densities to 0.3-0.5
 * 2. Adjust one density at a time to taste
 * 3. Use RANDOMNESS sparingly (>0.5 gets chaotic quickly)
 * 4. SWING works best in 0.2-0.6 range
 * 5. Switch modes (BEATS_MODE) for dramatic pattern changes
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class DrumsTool @Inject constructor(
    private val synthController: SynthController
) : Tool<DrumsControlArgs, DrumsControlResult>(
    argsSerializer = DrumsControlArgs.serializer(),
    resultSerializer = DrumsControlResult.serializer(),
    name = "drums_control",
    description = """
        Control drum synthesis and drum beat patterns.
        
        808 DRUMS - Manual triggering of three analog-style drum voices:
        Bass Drum: DRUM_BD_FREQ, DRUM_BD_TONE, DRUM_BD_DECAY, DRUM_BD_AFM, DRUM_BD_SFM, DRUM_BD_TRIGGER
        Snare Drum: DRUM_SD_FREQ, DRUM_SD_TONE, DRUM_SD_DECAY, DRUM_SD_SNAPPY, DRUM_SD_TRIGGER
        Hi-Hat: DRUM_HH_FREQ, DRUM_HH_TONE, DRUM_HH_DECAY, DRUM_HH_NOISY, DRUM_HH_TRIGGER
        
        DRUM BEATS - Algorithmic pattern generator:
        Pattern: BEATS_X, BEATS_Y, BEATS_DENSITY_1/2/3, BEATS_RUN (0=stop, 1=run)
        Timing: BEATS_BPM (60-200 mapped to 0-1), BEATS_SWING (0=straight, 1=swing)
        Mode: BEATS_MODE (0=DRUMS, 1=EUCLIDEAN)
        Euclidean: BEATS_EUCLIDEAN_LENGTH_1/2/3 (1-32 steps)
        Feel: BEATS_RANDOMNESS (0=locked, 1=chaotic), BEATS_MIX (0=off, 1=full)
        
        Values should be between 0.0 and 1.0.
        For EUCLIDEAN_LENGTH: value is scaled 0.0-1.0 → 1-32 steps internally.
        Use BEATS_RUN=1.0 to start the sequencer, 0.0 to stop.
    """.trimIndent()
) {
    // Track current values for each control to enable smooth ramping
    private val currentValues = mutableMapOf<String, Float>()
    
    // Ramp configuration: 500ms over ~25 steps = 20ms per step
    private val rampDurationMs = 500L
    private val rampSteps = 25

    override suspend fun execute(args: DrumsControlArgs): DrumsControlResult {
        val normalizedValue = args.value.coerceIn(0f, 1f)
        
        // Map friendly aliases to actual system IDs (which are lowercase)
        val targetId = when (val id = args.controlId.uppercase()) {
            // 808 Bass Drum controls
            "DRUM_BD_FREQ" -> "drum_bd_freq"
            "DRUM_BD_TONE" -> "drum_bd_tone"
            "DRUM_BD_DECAY" -> "drum_bd_decay"
            "DRUM_BD_AFM", "DRUM_BD_ATTACK_FM" -> "drum_bd_afm"
            "DRUM_BD_SFM", "DRUM_BD_SELF_FM" -> "drum_bd_sfm"
            "DRUM_BD_TRIGGER" -> "drum_bd_trigger"

            // 808 Snare Drum controls
            "DRUM_SD_FREQ" -> "drum_sd_freq"
            "DRUM_SD_TONE" -> "drum_sd_tone"
            "DRUM_SD_DECAY" -> "drum_sd_decay"
            "DRUM_SD_SNAPPY", "DRUM_SD_SNAPPINESS" -> "drum_sd_snappy"
            "DRUM_SD_TRIGGER" -> "drum_sd_trigger"

            // 808 Hi-Hat controls
            "DRUM_HH_FREQ" -> "drum_hh_freq"
            "DRUM_HH_TONE" -> "drum_hh_tone"
            "DRUM_HH_DECAY" -> "drum_hh_decay"
            "DRUM_HH_NOISY", "DRUM_HH_NOISINESS" -> "drum_hh_noisy"
            "DRUM_HH_TRIGGER" -> "drum_hh_trigger"
            
            // Drum Beats pattern controls
            "BEATS_X" -> "beats_x"
            "BEATS_Y" -> "beats_y"
            "BEATS_DENSITY_1" -> "beats_density_1"
            "BEATS_DENSITY_2" -> "beats_density_2"
            "BEATS_DENSITY_3" -> "beats_density_3"
            "BEATS_RUN" -> "beats_run"
            "BEATS_BPM" -> "beats_bpm"
            "BEATS_MODE" -> "beats_mode"
            "BEATS_EUCLIDEAN_LENGTH_1" -> "beats_euclidean_length_1"
            "BEATS_EUCLIDEAN_LENGTH_2" -> "beats_euclidean_length_2"
            "BEATS_EUCLIDEAN_LENGTH_3" -> "beats_euclidean_length_3"
            "BEATS_RANDOMNESS" -> "beats_randomness"
            "BEATS_SWING" -> "beats_swing"
            "BEATS_MIX" -> "beats_mix"
            
            else -> args.controlId.lowercase()
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
        
        // Format helpful message based on control type
        val message = when {
            targetId.startsWith("drum_bd_") -> "Set bass drum ${targetId.removePrefix("drum_bd_")} to ${(normalizedValue * 100).toInt()}%"
            targetId.startsWith("drum_sd_") -> "Set snare drum ${targetId.removePrefix("drum_sd_")} to ${(normalizedValue * 100).toInt()}%"
            targetId.startsWith("drum_hh_") -> "Set hi-hat ${targetId.removePrefix("drum_hh_")} to ${(normalizedValue * 100).toInt()}%"
            targetId.startsWith("beats_") -> {
                when {
                    targetId == "beats_run" -> if (normalizedValue > 0.5f) "Started beat sequencer" else "Stopped beat sequencer"
                    targetId == "beats_mode" -> if (normalizedValue > 0.5f) "Switched to EUCLIDEAN mode" else "Switched to DRUMS mode"
                    targetId.contains("euclidean_length") -> {
                        val length = ((normalizedValue * 31) + 1).toInt().coerceIn(1, 32)
                        "Set ${targetId.removePrefix("beats_")} to $length steps"
                    }
                    targetId == "beats_bpm" -> {
                        val bpm = (60 + normalizedValue * 140).toInt()
                        "Set tempo to $bpm BPM"
                    }
                    else -> "Set ${targetId.removePrefix("beats_")} to ${(normalizedValue * 100).toInt()}%"
                }
            }
            else -> "Ramped $targetId to ${(normalizedValue * 100).toInt()}%"
        }
        
        return DrumsControlResult(
            success = true,
            message = message
        )
    }
}
