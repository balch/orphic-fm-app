package org.balch.orpheus.features.evo.strategy

/**
 * Mutate Strategy - Sudden Parameter Jumps
 *
 * Creates sudden, dramatic parameter changes for glitchy, surprising effects.
 * Unlike Drift's gradual changes, Mutate makes abrupt jumps.
 *
 * SPEED (Knob 1): Controls how often mutations occur
 * CHAOS (Knob 2): Controls mutation magnitude and how many parameters change at once
 */
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.evo.AudioEvolutionStrategy
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.random.Random

@Inject
@ContributesIntoSet(AppScope::class)
class MutateStrategy(
    private val synthController: SynthController
) : AudioEvolutionStrategy {

    private val log = logging("MutateStrategy")

    override val id = "mutate"
    override val name = "Mutate"
    override val color = OrpheusColors.strategyMagenta // Pink/Magenta - chaotic/energetic
    override val knob1Label = "SPEED"
    override val knob2Label = "CHAOS"

    private var speedKnob = 0.5f
    private var chaosKnob = 0.5f
    private var mutationCount = 0

    override fun setKnob1(value: Float) {
        speedKnob = value.coerceIn(0f, 1f)
        log.debug { "SPEED set to $speedKnob" }
    }

    override fun setKnob2(value: Float) {
        chaosKnob = value.coerceIn(0f, 1f)
        log.debug { "CHAOS set to $chaosKnob" }
    }

    private fun emit(id: PluginControlId, value: Float) {
        synthController.setPluginControl(id, FloatValue(value), ControlEventOrigin.EVO)
    }

    // All mutable parameters with their plugin control IDs and getters
    private enum class MutableParam(val getParam: (SynthEngine) -> Pair<PluginControlId, Float>) {
        DRIVE({ engine -> DistortionSymbol.DRIVE.controlId to engine.getDrive() }),
        DISTORTION_MIX({ engine -> DistortionSymbol.MIX.controlId to engine.getDistortionMix() }),
        DELAY_MIX({ engine -> DelaySymbol.MIX.controlId to engine.getDelayMix() }),
        DELAY_FEEDBACK({ engine -> DelaySymbol.FEEDBACK.controlId to engine.getDelayFeedback() }),
        DELAY_TIME_0({ engine -> DelaySymbol.TIME_1.controlId to engine.getDelayTime(0) }),
        DELAY_TIME_1({ engine -> DelaySymbol.TIME_2.controlId to engine.getDelayTime(1) }),
        // Note: Quad 3 (index 2) is reserved for Drone mode and not mutated
        QUAD_PITCH_0({ engine -> VoiceSymbol.quadPitch(0).controlId to engine.getQuadPitch(0) }),
        QUAD_PITCH_1({ engine -> VoiceSymbol.quadPitch(1).controlId to engine.getQuadPitch(1) }),
        QUAD_HOLD_0({ engine -> VoiceSymbol.quadHold(0).controlId to engine.getQuadHold(0) }),
        QUAD_HOLD_1({ engine -> VoiceSymbol.quadHold(1).controlId to engine.getQuadHold(1) }),
        LFO_0({ engine -> DuoLfoSymbol.FREQ_A.controlId to engine.getHyperLfoFreq(0) }),
        LFO_1({ engine -> DuoLfoSymbol.FREQ_B.controlId to engine.getHyperLfoFreq(1) }),
        VIBRATO({ engine -> VoiceSymbol.VIBRATO.controlId to engine.getVibrato() }),
        VOICE_COUPLING({ engine -> VoiceSymbol.COUPLING.controlId to engine.getVoiceCoupling() })
    }

    override suspend fun evolve(engine: SynthEngine) {
        val r = Random
        mutationCount++

        // Number of parameters to mutate scales with chaos (1-6 params)
        val numMutations = 1 + (chaosKnob * 5).toInt()

        // Mutation magnitude scales with chaos (10% to 50% of full range)
        val magnitude = 0.1f + (chaosKnob * 0.4f)

        // Select random parameters to mutate
        val paramsToMutate = MutableParam.entries.shuffled().take(numMutations)

        val mutations = mutableListOf<String>()

        for (param in paramsToMutate) {
            val (pluginId, currentValue) = param.getParam(engine)

            // Random direction for jump
            val direction = if (r.nextBoolean()) 1f else -1f
            val jumpAmount = r.nextFloat() * magnitude * direction

            // Compute new value with appropriate clamping
            val newValue = when (param) {
                MutableParam.DRIVE -> (currentValue + jumpAmount).coerceIn(0f, 0.8f)
                MutableParam.DELAY_FEEDBACK -> (currentValue + jumpAmount * 0.5f).coerceIn(0f, 0.85f)
                MutableParam.DELAY_TIME_0, MutableParam.DELAY_TIME_1 ->
                    (currentValue + jumpAmount).coerceIn(0.05f, 1f)
                MutableParam.QUAD_PITCH_0, MutableParam.QUAD_PITCH_1 ->
                    (currentValue + jumpAmount * 0.3f).coerceIn(0.2f, 0.8f)
                MutableParam.VIBRATO -> (currentValue + jumpAmount * 0.5f).coerceIn(0f, 0.6f)
                MutableParam.VOICE_COUPLING -> (currentValue + jumpAmount * 0.3f).coerceIn(0f, 0.5f)
                else -> (currentValue + jumpAmount).coerceIn(0f, 1f)
            }

            emit(pluginId, newValue)
            mutations.add("${param.name.lowercase()}: $currentValue->$newValue")
        }

        log.debug { "Mutation #$mutationCount: ${mutations.joinToString(", ")}" }
    }

    override fun onActivate() {
        mutationCount = 0
        log.debug { "Activated (SPEED=$speedKnob, CHAOS=$chaosKnob)" }
    }

    override fun onDeactivate() {
        log.debug { "Deactivated after $mutationCount mutations" }
        mutationCount = 0
    }
}
