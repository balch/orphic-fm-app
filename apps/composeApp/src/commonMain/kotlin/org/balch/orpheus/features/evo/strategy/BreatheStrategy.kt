package org.balch.orpheus.features.evo.strategy

/**
 * Breathe Strategy - Cyclic Oscillation
 * 
 * Oscillates parameters using sine waves for rhythmic, breathing textures.
 * Creates predictable, hypnotic modulation patterns.
 * 
 * SPEED (Knob 1): Controls oscillation frequency
 * DEPTH (Knob 2): Controls modulation amplitude (how much parameters change)
 */
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.features.evo.AudioEvolutionStrategy
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.PI
import kotlin.math.sin

@Inject
@ContributesIntoSet(AppScope::class)
class BreatheStrategy(
    private val synthController: SynthController
) : AudioEvolutionStrategy {

    private val log = logging("BreatheStrategy")

    override val id = "breathe"
    override val name = "Breathe"
    override val color = OrpheusColors.strategyBlue // Blue - calm/rhythmic
    override val knob1Label = "SPEED"
    override val knob2Label = "DEPTH"

    private var speedKnob = 0.5f
    private var depthKnob = 0.5f
    private var phase = 0.0
    private var tickCount = 0

    override fun setKnob1(value: Float) {
        speedKnob = value.coerceIn(0f, 1f)
        log.debug { "SPEED set to $speedKnob" }
    }

    override fun setKnob2(value: Float) {
        depthKnob = value.coerceIn(0f, 1f)
        log.debug { "DEPTH set to $depthKnob" }
    }

    private fun emit(controlId: String, value: Float) {
        synthController.emitControlChange(controlId, value, ControlEventOrigin.EVO)
    }

    override suspend fun evolve(engine: SynthEngine) {
        if (depthKnob <= 0.01f) return

        // Phase increment - faster oscillation at higher speeds
        val phaseIncrement = 0.05 + (speedKnob * 0.15)
        phase += phaseIncrement
        tickCount++

        // Multiple sine waves at different frequencies for complex breathing
        val sinA = (sin(phase) * 0.5 + 0.5).toFloat()                      // Primary cycle
        val sinB = (sin(phase * 0.7 + PI / 3) * 0.5 + 0.5).toFloat()       // Secondary cycle
        val sinC = (sin(phase * 1.3 + PI / 2) * 0.5 + 0.5).toFloat()       // Tertiary cycle

        // Modulation depth scales with knob
        val depth = depthKnob

        // DelayMix breathes with primary sine
        val targetDelayMix = 0.2f + (sinA * 0.5f * depth)
        val currentDelayMix = engine.getDelayMix()
        val newDelayMix = currentDelayMix + (targetDelayMix - currentDelayMix) * 0.15f
        emit(ControlIds.DELAY_MIX, newDelayMix)

        // Drive breathes with secondary sine (slightly out of phase)
        val targetDrive = 0.1f + (sinB * 0.35f * depth)
        val currentDrive = engine.getDrive()
        val newDrive = currentDrive + (targetDrive - currentDrive) * 0.1f
        emit(ControlIds.DRIVE, newDrive)

        // Quad holds breathe in counterpoint
        val targetHold1 = 0.3f + (sinA * 0.5f * depth)
        val targetHold2 = 0.3f + ((1f - sinA) * 0.5f * depth) // Inverse
        
        val currentHold0 = engine.getQuadHold(0)
        val currentHold1 = engine.getQuadHold(1)
        val newHold0 = currentHold0 + (targetHold1 - currentHold0) * 0.08f
        val newHold1 = currentHold1 + (targetHold2 - currentHold1) * 0.08f
        emit(ControlIds.quadHold(0), newHold0)
        emit(ControlIds.quadHold(1), newHold1)

        // Vibrato breathes with tertiary sine
        val targetVibrato = 0.05f + (sinC * 0.25f * depth)
        emit(ControlIds.VIBRATO, targetVibrato)

        // Log every 20 ticks to avoid spam
        if (tickCount % 20 == 0) {
            log.debug { 
                "Tick $tickCount: phase=$phase, delayMix=$newDelayMix, drive=$newDrive, vibrato=$targetVibrato"
            }
        }
    }

    override fun onActivate() {
        phase = 0.0
        tickCount = 0
        log.debug { "Activated (SPEED=$speedKnob, DEPTH=$depthKnob)" }
    }

    override fun onDeactivate() {
        log.debug { "Deactivated after $tickCount ticks" }
        phase = 0.0
        tickCount = 0
    }
}
