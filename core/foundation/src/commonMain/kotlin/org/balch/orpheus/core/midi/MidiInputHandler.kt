package org.balch.orpheus.core.midi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import kotlin.math.roundToInt

/**
 * MIDI-specific input handler that translates MIDI events to SynthController events.
 * 
 * This class owns all MIDI protocol-specific logic:
 * - Note to voice mapping
 * - CC to control mapping
 * - Button toggle/cycle detection
 * - Learn mode integration
 * 
 * It calls through to SynthController for routing events to ViewModels.
 */
@SingleIn(AppScope::class)
@Inject
class MidiInputHandler(
    private val synthController: SynthController,
    private val stateHolder: MidiMappingStateHolder
) : MidiEventListener {

    // Track last CC values for button toggle detection
    private val lastCcValues = mutableMapOf<String, Float>()
    private val lastRawCcValues = mutableMapOf<String, Float>()

    // ═══════════════════════════════════════════════════════════
    // MidiEventListener Implementation
    // ═══════════════════════════════════════════════════════════

    override fun onNoteOn(note: Int, velocity: Int) {
        // First check if we're in learn mode and should capture this note
        if (stateHolder.tryLearnNote(note)) {
            return
        }
        
        // Voice trigger
        stateHolder.getVoiceForNote(note)?.let { voiceIndex ->
            synthController.emitPulseStart(voiceIndex)
        }

        // Control trigger (for buttons mapped to notes)
        stateHolder.getControlForNote(note)?.let { controlId ->
            if (velocity > 0) {
                if (isCycleControl(controlId)) {
                    cycleControl(controlId, 3)
                } else {
                    toggleControl(controlId)
                }
            }
        }
    }

    override fun onNoteOff(note: Int) {
        stateHolder.getVoiceForNote(note)?.let { voiceIndex ->
            synthController.emitPulseEnd(voiceIndex)
        }
    }

    override fun onControlChange(controller: Int, value: Int) {
        val normalized = value / 127f
        
        // First check if we're in learn mode and should capture this CC
        if (stateHolder.tryLearnCC(controller)) {
            return
        }
        
        val controlId = stateHolder.getControlForCC(controller)
        controlId?.let {
            applyCCToControl(it, normalized)
        }
    }

    override fun onPitchBend(value: Int) {
        // Could apply to quad pitch or other parameter
    }

    // ═══════════════════════════════════════════════════════════
    // CC Processing Logic
    // ═══════════════════════════════════════════════════════════

    private fun applyCCToControl(controlId: String, value: Float) {
        val isCycle = isCycleControl(controlId)

        var effectiveValue = value

        if (!isCycle) {
            val lastRaw = lastRawCcValues[controlId] ?: 0f
            val isJumpUp = value >= 0.9f && lastRaw < 0.5f
            val isJumpDown = value < 0.1f && lastRaw > 0.5f
            val lastEffective = lastCcValues[controlId] ?: 0f

            effectiveValue = when {
                isJumpUp -> if (lastEffective > 0.5f) 0f else 1f
                isJumpDown -> lastEffective
                else -> value
            }
        }

        dispatchControlChange(controlId, effectiveValue)
        lastCcValues[controlId] = effectiveValue
        lastRawCcValues[controlId] = value
    }

    private fun toggleControl(controlId: String) {
        val lastValue = lastCcValues[controlId] ?: 0f
        val newValue = if (lastValue > 0.5f) 0f else 1f
        lastCcValues[controlId] = newValue
        dispatchControlChange(controlId, newValue)
    }

    private fun cycleControl(controlId: String, numStates: Int) {
        val lastValue = lastCcValues[controlId] ?: 0f
        val currentIndex = (lastValue * (numStates - 1)).roundToInt()
        val nextIndex = (currentIndex + 1) % numStates
        val newValue = nextIndex.toFloat() / (numStates - 1)
        lastCcValues[controlId] = newValue
        dispatchControlChange(controlId, newValue)
    }

    private fun isCycleControl(controlId: String): Boolean {
        return controlId == DuoLfoSymbol.MODE.controlId.key ||
                (controlId.startsWith("$VOICE_URI:") && controlId.contains("duo_mod_source"))
    }

    private fun dispatchControlChange(controlId: String, value: Float) {
        // Try to route through the typed plugin control system first
        val routed = synthController.setPluginControlByKey(
            controlId,
            PortValue.FloatValue(value),
            ControlEventOrigin.MIDI
        )
        // Fall back to legacy event emission for non-plugin controls
        if (!routed) {
            synthController.emitControlChange(controlId, value, ControlEventOrigin.MIDI)
        }
    }
}
