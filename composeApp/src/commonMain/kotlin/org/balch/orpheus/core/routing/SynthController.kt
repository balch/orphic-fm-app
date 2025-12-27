package org.balch.orpheus.core.routing

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Origin of a control event.
 */
enum class ControlEventOrigin {
    MIDI,      // External MIDI controller or Keyboard
    UI,        // Manual UI interaction (Knobs/Sliders)
    SEQUENCER, // Internal Automation/Sequencer
    TIDAL      // Tidal Cycles OSC input (future)
}

/**
 * Control event data for flow-based routing.
 */
data class ControlEvent(
    val controlId: String, 
    val value: Float,
    val origin: ControlEventOrigin = ControlEventOrigin.MIDI
)

/**
 * Generic controller for routing synth parameter events.
 * 
 * This is a protocol-agnostic event bus that ViewModels subscribe to.
 * Input handlers (MIDI, Tidal, etc.) emit events through this controller.
 * Internal producers (TweakSequencer, UI) also emit through here.
 * 
 * This separation allows multiple input sources without ViewModels
 * needing to know about specific protocols like MIDI or OSC.
 */
@SingleIn(AppScope::class)
@Inject
class SynthController {

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
    
    private val _onControlChange = MutableSharedFlow<ControlEvent>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Public read-only flows for ViewModels to subscribe
    val onPulseStart: Flow<Int> = _onPulseStart.asSharedFlow()
    val onPulseEnd: Flow<Int> = _onPulseEnd.asSharedFlow()
    val onControlChange: Flow<ControlEvent> = _onControlChange.asSharedFlow()

    /**
     * Emit a pulse start event for a voice.
     * Called by input handlers when a note-on is received.
     */
    fun emitPulseStart(voiceIndex: Int) {
        _onPulseStart.tryEmit(voiceIndex)
    }

    /**
     * Emit a pulse end event for a voice.
     * Called by input handlers when a note-off is received.
     */
    fun emitPulseEnd(voiceIndex: Int) {
        _onPulseEnd.tryEmit(voiceIndex)
    }

    /**
     * Emit a control change event.
     * Called by input handlers, UI controls, and sequencer.
     */
    fun emitControlChange(
        controlId: String, 
        value: Float, 
        origin: ControlEventOrigin = ControlEventOrigin.UI
    ) {
        _onControlChange.tryEmit(ControlEvent(controlId, value, origin))
    }
}
