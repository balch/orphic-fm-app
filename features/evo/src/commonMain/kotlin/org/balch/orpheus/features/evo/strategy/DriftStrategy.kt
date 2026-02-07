package org.balch.orpheus.features.evo.strategy

/**
 * Drift Strategy - Random Walk Evolution
 *
 * Slowly drifts parameters around their current values.
 * Creates organic, non-repetitive textures that evolve gradually.
 *
 * SPEED (Knob 1): Controls how often parameters change (tick rate)
 * RANGE (Knob 2): Controls how far parameters can drift per step
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
class DriftStrategy(
    private val synthController: SynthController
) : AudioEvolutionStrategy {

    private val log = logging("DriftStrategy")

    override val id = "drift"
    override val name = "Drift"
    override val color = OrpheusColors.strategyGreen // Green - organic/natural
    override val knob1Label = "SPEED"
    override val knob2Label = "RANGE"

    private var speedKnob = 0.5f
    private var rangeKnob = 0.5f

    override fun setKnob1(value: Float) {
        speedKnob = value.coerceIn(0f, 1f)
        log.debug { "SPEED set to $speedKnob" }
    }

    override fun setKnob2(value: Float) {
        rangeKnob = value.coerceIn(0f, 1f)
        log.debug { "RANGE set to $rangeKnob" }
    }

    private fun emit(id: PluginControlId, value: Float) {
        synthController.setPluginControl(id, FloatValue(value), ControlEventOrigin.EVO)
    }

    override suspend fun evolve(engine: SynthEngine) {
        val r = Random

        // Probability of changing a parameter this tick (higher range = more changes)
        val changeChance = 0.15f + (rangeKnob * 0.35f)

        // Step size scales with range knob (0.5% to 5% of full range)
        val stepSize = 0.005f + (rangeKnob * 0.045f)

        fun nudge(current: Float): Float {
            val delta = (r.nextFloat() - 0.5f) * stepSize
            return (current + delta).coerceIn(0f, 1f)
        }

        val changedParams = mutableListOf<String>()

        // Global FX Drift
        if (r.nextFloat() < changeChance) {
            val old = engine.getDrive()
            val new = nudge(old)
            emit(DistortionSymbol.DRIVE.controlId, new)
            changedParams.add("drive: $old->$new")
        }
        if (r.nextFloat() < changeChance) {
            val old = engine.getDistortionMix()
            val new = nudge(old)
            emit(DistortionSymbol.MIX.controlId, new)
            changedParams.add("distMix: $old->$new")
        }
        if (r.nextFloat() < changeChance) {
            val old = engine.getDelayMix()
            val new = nudge(old)
            emit(DelaySymbol.MIX.controlId, new)
            changedParams.add("delayMix: $old->$new")
        }
        if (r.nextFloat() < changeChance) {
            val old = engine.getDelayFeedback()
            val new = nudge(old)
            emit(DelaySymbol.FEEDBACK.controlId, new)
            changedParams.add("delayFb: $old->$new")
        }

        // Quad pitch drift (Quads 1 and 2 only - Quad 3 is reserved for Drone)
        for (q in 0..1) {
            if (r.nextFloat() < changeChance * 0.5f) {
                val old = engine.getQuadPitch(q)
                val new = nudge(old)
                emit(VoiceSymbol.quadPitch(q).controlId, new)
                changedParams.add("quadPitch$q: $old->$new")
            }
        }

        // LFO speed drift
        if (r.nextFloat() < changeChance * 0.3f) {
            val idx = r.nextInt(2)
            val old = engine.getHyperLfoFreq(idx)
            val new = nudge(old)
            val lfoId = if (idx == 0) DuoLfoSymbol.FREQ_A.controlId else DuoLfoSymbol.FREQ_B.controlId
            emit(lfoId, new)
            changedParams.add("lfo$idx: $old->$new")
        }

        if (changedParams.isNotEmpty()) {
            log.debug { "Evolved: ${changedParams.joinToString(", ")}" }
        }
    }

    override fun onActivate() {
        log.debug { "Activated (SPEED=$speedKnob, RANGE=$rangeKnob)" }
    }

    override fun onDeactivate() {
        log.debug { "Deactivated" }
    }
}
