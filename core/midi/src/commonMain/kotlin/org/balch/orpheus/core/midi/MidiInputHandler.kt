package org.balch.orpheus.core.midi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.MidiNoteEvent
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dxLockEngineControl
import org.balch.orpheus.core.audio.isDrumModControl
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortType
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.ports.PortRegistry
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
    private val stateHolder: MidiMappingStateHolder,
    private val portRegistry: PortRegistry,
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

        synthController.emitMidiNote(
            MidiNoteEvent(note, velocity, on = true, voice = stateHolder.getVoiceForNote(note))
        )

        // Voice trigger
        stateHolder.getVoiceForNote(note)?.let { voiceIndex ->
            synthController.emitPulseStart(voiceIndex)
        }

        // Control trigger (for buttons mapped to notes)
        stateHolder.getControlForNote(note)?.let { controlId ->
            if (isMomentary(controlId)) {
                dispatchControlChange(controlId, if (velocity > 0) 1f else 0f)
            } else if (velocity > 0) {
                val port = steppedPort(controlId)
                if (port != null) {
                    cycleControl(controlId, port)
                } else {
                    toggleControl(controlId)
                }
            }
        }
    }

    override fun onNoteOff(note: Int) {
        synthController.emitMidiNote(
            MidiNoteEvent(note, 0, on = false, voice = stateHolder.getVoiceForNote(note))
        )
        stateHolder.getVoiceForNote(note)?.let { voiceIndex ->
            synthController.emitPulseEnd(voiceIndex)
        }
        stateHolder.getControlForNote(note)?.takeIf(::isMomentary)?.let { controlId ->
            dispatchControlChange(controlId, 0f)
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
        if (isDxLocked(controlId)) return
        var effectiveValue = value

        if (steppedPort(controlId) == null && !isMomentary(controlId)) {
            val lastRaw = lastRawCcValues[controlId] ?: 0f
            val isJumpUp = value >= 0.9f && lastRaw < 0.5f
            val isJumpDown = value < 0.1f && lastRaw > 0.5f
            val lastEffective = lastToggleValue(controlId)

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

    /** On DX engines harmonics picks a patch, so a learned CC must leave it alone. */
    private fun isDxLocked(controlId: String): Boolean {
        val engineKey = dxLockEngineControl(controlId) ?: return false
        val id = PluginControlId.parse(engineKey) ?: return false
        val raw = synthController.getPluginControl(id)?.asInt() ?: return false
        val engine = if (isDrumModControl(controlId)) OrpheusEngineId.entries.getOrNull(raw)
        else OrpheusEngineId.fromId(raw)
        return engine?.forcePinHarmonics == true
    }

    /** The panel can change a port between presses, so a toggle starts from the port itself. */
    private fun lastToggleValue(controlId: String): Float {
        val port = portRegistry.controlPorts[controlId]?.second
        val current = when {
            port?.type == PortType.BOOLEAN -> livePortValue(controlId)?.asFloat()
            port?.stateCount == 2 -> statePosition(controlId, port)?.toFloat()
            else -> null
        }
        return current ?: lastCcValues[controlId] ?: 0f
    }

    private fun livePortValue(controlId: String): PortValue? =
        PluginControlId.parse(controlId)?.let(synthController::getPluginControl)

    /** Which of an int port's states it is in, counted from the port's min. */
    private fun statePosition(controlId: String, port: ControlPort): Int? =
        livePortValue(controlId)?.let { it.asInt() - port.min.toInt() }

    private fun toggleControl(controlId: String) {
        val lastValue = lastToggleValue(controlId)
        val newValue = if (lastValue > 0.5f) 0f else 1f
        lastCcValues[controlId] = newValue
        dispatchControlChange(controlId, newValue)
    }

    private fun cycleControl(controlId: String, port: ControlPort) {
        val numStates = port.stateCount
        val currentIndex = statePosition(controlId, port)
            ?: ((lastCcValues[controlId] ?: 0f) * (numStates - 1)).roundToInt()
        val nextIndex = (currentIndex + 1).mod(numStates)
        val newValue = nextIndex.toFloat() / (numStates - 1)
        lastCcValues[controlId] = newValue
        dispatchControlChange(controlId, newValue)
    }

    /** An int port with three or more states. Two-state ports stay buttons, and the CC toggles them. */
    private fun steppedPort(controlId: String): ControlPort? =
        portRegistry.controlPorts[controlId]?.second?.takeIf { it.stateCount > 2 }

    private fun isMomentary(controlId: String): Boolean =
        portRegistry.controlPorts[controlId]?.second?.momentary == true

    private fun dispatchControlChange(controlId: String, value: Float) {
        // Try to route through the typed plugin control system first
        val routed = synthController.setPluginControlByKey(
            controlId,
            portValueFor(controlId, value),
            ControlEventOrigin.MIDI
        )
        // Fall back to legacy event emission for non-plugin controls
        if (!routed) {
            synthController.emitControlChange(controlId, value, ControlEventOrigin.MIDI)
        }
    }

    /** A 0..1 float truncates on an int port and strands a float port's wider range, so a CC spans the port. */
    private fun portValueFor(controlId: String, value: Float): PortValue {
        val port = portRegistry.controlPorts[controlId]?.second ?: return PortValue.FloatValue(value)
        val position = value.coerceIn(0f, 1f)
        return when {
            port.stateCount >= 2 -> PortValue.IntValue(port.min.toInt() + (position * (port.stateCount - 1)).roundToInt())
            port.type == PortType.FLOAT -> PortValue.FloatValue(port.min + position * (port.max - port.min))
            else -> PortValue.FloatValue(value)
        }
    }
}
