package org.balch.orpheus.features.evo.strategy

import androidx.compose.ui.graphics.Color
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.features.evo.AudioEvolutionStrategy
import kotlin.random.Random

/**
 * Drift Strategy - Random Walk Evolution
 * 
 * Slowly drifts parameters around their current values.
 * Creates organic, non-repetitive textures that evolve gradually.
 * 
 * SPEED (Knob 1): Controls how often parameters change (tick rate)
 * RANGE (Knob 2): Controls how far parameters can drift per step
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DriftStrategy(
    private val synthController: SynthController
) : AudioEvolutionStrategy {

    private val log = logging("DriftStrategy")

    override val id = "drift"
    override val name = "Drift"
    override val color = Color(0xFF4CAF50) // Green - organic/natural
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

        fun emit(controlId: String, value: Float) {
            synthController.emitControlChange(controlId, value, ControlEventOrigin.EVO)
        }

        val changedParams = mutableListOf<String>()

        // Global FX Drift
        if (r.nextFloat() < changeChance) {
            val old = engine.getDrive()
            val new = nudge(old)
            emit(ControlIds.DRIVE, new)
            changedParams.add("drive: $old->$new")
        }
        if (r.nextFloat() < changeChance) {
            val old = engine.getDistortionMix()
            val new = nudge(old)
            emit(ControlIds.DISTORTION_MIX, new)
            changedParams.add("distMix: $old->$new")
        }
        if (r.nextFloat() < changeChance) {
            val old = engine.getDelayMix()
            val new = nudge(old)
            emit(ControlIds.DELAY_MIX, new)
            changedParams.add("delayMix: $old->$new")
        }
        if (r.nextFloat() < changeChance) {
            val old = engine.getDelayFeedback()
            val new = nudge(old)
            emit(ControlIds.DELAY_FEEDBACK, new)
            changedParams.add("delayFb: $old->$new")
        }
        
        // Quad pitch drift (all 3 quads)
        for (q in 0..2) {
            if (r.nextFloat() < changeChance * 0.5f) {
                val old = engine.getQuadPitch(q)
                val new = nudge(old)
                emit(ControlIds.quadPitch(q), new)
                changedParams.add("quadPitch$q: $old->$new")
            }
        }
        
        // LFO speed drift
        if (r.nextFloat() < changeChance * 0.3f) {
            val idx = r.nextInt(2)
            val old = engine.getHyperLfoFreq(idx)
            val new = nudge(old)
            val controlId = if (idx == 0) ControlIds.HYPER_LFO_A else ControlIds.HYPER_LFO_B
            emit(controlId, new)
            changedParams.add("lfo$idx: $old->$new")
        }

        if (changedParams.isNotEmpty()) {
            log.debug { "Evolved: ${changedParams.joinToString(", ")}" }
        }
    }

    override fun onActivate() {
        log.info { "Activated (SPEED=$speedKnob, RANGE=$rangeKnob)" }
    }

    override fun onDeactivate() {
        log.info { "Deactivated" }
    }
}
