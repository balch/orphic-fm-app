package org.balch.orpheus.core.midi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.roundToInt

/**
 * MIDI event data classes for flow-based routing.
 */
data class MidiPulseEvent(val voiceIndex: Int)
data class MidiControlEvent(val controlId: String, val value: Float)

/**
 * Routes MIDI events to the appropriate feature ViewModels.
 * 
 * This router exposes flows that ViewModels can subscribe to, rather than
 * directly calling ViewModel methods. This allows for proper dependency
 * injection without circular dependencies.
 * 
 * Uses MidiMappingStateHolder (a singleton) for mapping lookups and learn mode,
 * so it shares the same state as MidiViewModel without needing a ViewModel reference.
 */
@SingleIn(AppScope::class)
@Inject
class MidiRouter(
    private val stateHolder: MidiMappingStateHolder
) {

    // Track last CC values for button toggle detection
    private val lastCcValues = mutableMapOf<String, Float>()
    private val lastRawCcValues = mutableMapOf<String, Float>()

    // Private mutable flows
    private val _onPulseStart = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    private val _onPulseEnd = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    private val _onControlChange = MutableSharedFlow<MidiControlEvent>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Public read-only flows
    val onPulseStart: Flow<Int> = _onPulseStart.asSharedFlow()
    val onPulseEnd: Flow<Int> = _onPulseEnd.asSharedFlow()
    val onControlChange: Flow<MidiControlEvent> = _onControlChange.asSharedFlow()

    fun createMidiEventListener(): MidiEventListener {
        return object : MidiEventListener {
            override fun onNoteOn(note: Int, velocity: Int) {
                // First check if we're in learn mode and should capture this note
                if (stateHolder.tryLearnNote(note)) {
                    return
                }
                
                // Voice trigger
                stateHolder.getVoiceForNote(note)?.let { voiceIndex ->
                    _onPulseStart.tryEmit(voiceIndex)
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
                    _onPulseEnd.tryEmit(voiceIndex)
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
        }
    }

    private fun applyCCToControl(controlId: String, value: Float) {
        val isCycleControl =
            controlId == MidiMappingState.Companion.ControlIds.HYPER_LFO_MODE ||
                    (controlId.startsWith("pair_") && controlId.endsWith("_mod_source"))

        var effectiveValue = value

        if (!isCycleControl) {
            val lastRaw = lastRawCcValues[controlId] ?: 0f
            val isJumpUp = value >= 0.9f && lastRaw < 0.5f
            val isJumpDown = value < 0.1f && lastRaw > 0.5f
            val lastEffective = lastCcValues[controlId] ?: 0f

            effectiveValue =
                when {
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
        return controlId == MidiMappingState.Companion.ControlIds.HYPER_LFO_MODE ||
                (controlId.startsWith("pair_") && controlId.endsWith("_mod_source"))
    }

    private fun dispatchControlChange(controlId: String, value: Float) {
        _onControlChange.tryEmit(MidiControlEvent(controlId, value))
    }
}
