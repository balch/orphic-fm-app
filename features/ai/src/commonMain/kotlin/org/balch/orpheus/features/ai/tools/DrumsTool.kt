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
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol

@Serializable
data class DrumsControlArgs(
    @property:LLMDescription("""
        Control ID for the drum parameter to adjust. Valid controls include:

        808 DRUMS (Analog synthesis):
        - DRUM_BD_FREQ, DRUM_BD_TONE, DRUM_BD_DECAY, DRUM_BD_AFM, DRUM_BD_SFM, DRUM_BD_TRIGGER
        - DRUM_SD_FREQ, DRUM_SD_TONE, DRUM_SD_DECAY, DRUM_SD_SNAPPY, DRUM_SD_TRIGGER
        - DRUM_HH_FREQ, DRUM_HH_TONE, DRUM_HH_DECAY, DRUM_HH_NOISY, DRUM_HH_TRIGGER

        DRUM BEATS (Pattern sequencer):
        - BEATS_RUN (start/stop), BPM (tempo), BEATS_X, BEATS_Y (pattern morph)
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
        - BPM: Maps 0.0-1.0 to 60-200 BPM (0.5 ≈ 130 BPM). Target 90-140 BPM for most songs.
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
        Timing: BPM (60-200 mapped to 0-1), BEATS_SWING (0=straight, 1=swing)
        Mode: BEATS_MODE (0=DRUMS, 1=EUCLIDEAN)
        Euclidean: BEATS_EUCLIDEAN_LENGTH_1/2/3 (1-32 steps)
        Feel: BEATS_RANDOMNESS (0=locked, 1=chaotic), BEATS_MIX (0=off, 1=full)

        Values should be between 0.0 and 1.0.
        For EUCLIDEAN_LENGTH: value is scaled 0.0-1.0 → 1-32 steps internally.
        Use BEATS_RUN=1.0 to start the sequencer, 0.0 to stop.
    """.trimIndent()
) {
    // Track current values for each control to enable smooth ramping
    private val currentValues = mutableMapOf<PluginControlId, Float>()

    // Ramp configuration: 500ms over ~25 steps = 20ms per step
    private val rampDurationMs = 500L
    private val rampSteps = 25

    /**
     * Resolve a friendly AI control name to its PortSymbol.
     * Returns null for special controls (triggers, run) or unknown IDs.
     */
    private fun resolvePortSymbol(id: String): PortSymbol? = when (id) {
        // 808 Bass Drum controls
        "DRUM_BD_FREQ" -> DrumSymbol.BD_FREQ
        "DRUM_BD_TONE" -> DrumSymbol.BD_TONE
        "DRUM_BD_DECAY" -> DrumSymbol.BD_DECAY
        "DRUM_BD_AFM", "DRUM_BD_ATTACK_FM" -> DrumSymbol.BD_P4
        "DRUM_BD_SFM", "DRUM_BD_SELF_FM" -> DrumSymbol.BD_P5

        // 808 Snare Drum controls
        "DRUM_SD_FREQ" -> DrumSymbol.SD_FREQ
        "DRUM_SD_TONE" -> DrumSymbol.SD_TONE
        "DRUM_SD_DECAY" -> DrumSymbol.SD_DECAY
        "DRUM_SD_SNAPPY", "DRUM_SD_SNAPPINESS" -> DrumSymbol.SD_P4

        // 808 Hi-Hat controls
        "DRUM_HH_FREQ" -> DrumSymbol.HH_FREQ
        "DRUM_HH_TONE" -> DrumSymbol.HH_TONE
        "DRUM_HH_DECAY" -> DrumSymbol.HH_DECAY
        "DRUM_HH_NOISY", "DRUM_HH_NOISINESS" -> DrumSymbol.HH_P4

        // Drum Beats pattern controls
        "BEATS_X" -> BeatsSymbol.X
        "BEATS_Y" -> BeatsSymbol.Y
        "BEATS_DENSITY_1" -> BeatsSymbol.density(0)
        "BEATS_DENSITY_2" -> BeatsSymbol.density(1)
        "BEATS_DENSITY_3" -> BeatsSymbol.density(2)
        "BPM", "BEATS_BPM" -> BeatsSymbol.BPM
        "BEATS_MODE" -> BeatsSymbol.MODE
        "BEATS_EUCLIDEAN_LENGTH_1" -> BeatsSymbol.euclidean(0)
        "BEATS_EUCLIDEAN_LENGTH_2" -> BeatsSymbol.euclidean(1)
        "BEATS_EUCLIDEAN_LENGTH_3" -> BeatsSymbol.euclidean(2)
        "BEATS_RANDOMNESS" -> BeatsSymbol.RANDOMNESS
        "BEATS_SWING" -> BeatsSymbol.SWING
        "BEATS_MIX" -> DrumSymbol.MIX

        else -> null
    }

    override suspend fun execute(args: DrumsControlArgs): DrumsControlResult {
        val normalizedValue = args.value.coerceIn(0f, 1f)
        val id = args.controlId.uppercase()

        // Special cases: triggers and run have no plugin port
        if (id in LEGACY_CONTROLS) {
            val legacyId = LEGACY_CONTROLS[id]!!
            synthController.emitControlChange(legacyId, normalizedValue, ControlEventOrigin.AI)
            return DrumsControlResult(success = true, message = "Set $id (legacy)")
        }

        val portSymbol = resolvePortSymbol(id)
            ?: return DrumsControlResult(
                success = false,
                message = "Unknown drum control: ${args.controlId}"
            )

        val targetId = portSymbol.controlId

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

        // Format helpful message based on control type
        val message = when {
            id.startsWith("DRUM_BD_") -> "Set bass drum ${portSymbol.displayName} to ${(normalizedValue * 100).toInt()}%"
            id.startsWith("DRUM_SD_") -> "Set snare drum ${portSymbol.displayName} to ${(normalizedValue * 100).toInt()}%"
            id.startsWith("DRUM_HH_") -> "Set hi-hat ${portSymbol.displayName} to ${(normalizedValue * 100).toInt()}%"
            id.startsWith("BEATS_") || id == "BPM" -> {
                when {
                    id == "BEATS_MODE" -> if (normalizedValue > 0.5f) "Switched to EUCLIDEAN mode" else "Switched to DRUMS mode"
                    id.contains("EUCLIDEAN_LENGTH") -> {
                        val length = ((normalizedValue * 31) + 1).toInt().coerceIn(1, 32)
                        "Set ${portSymbol.displayName} to $length steps"
                    }
                    id == "BPM" || id == "BEATS_BPM" -> {
                        val bpm = (60 + normalizedValue * 140).toInt()
                        "Set tempo to $bpm BPM"
                    }
                    else -> "Set ${portSymbol.displayName} to ${(normalizedValue * 100).toInt()}%"
                }
            }
            else -> "Ramped ${portSymbol.displayName} to ${(normalizedValue * 100).toInt()}%"
        }

        return DrumsControlResult(
            success = true,
            message = message
        )
    }

    companion object {
        /** Controls that have no PluginControlId — kept as legacy emitControlChange */
        private val LEGACY_CONTROLS = mapOf(
            "DRUM_BD_TRIGGER" to "drum_bd_trigger",
            "DRUM_SD_TRIGGER" to "drum_sd_trigger",
            "DRUM_HH_TRIGGER" to "drum_hh_trigger",
            "BEATS_RUN" to "beats_run",
        )
    }
}
