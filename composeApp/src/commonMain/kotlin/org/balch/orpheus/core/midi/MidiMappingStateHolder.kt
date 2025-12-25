package org.balch.orpheus.core.midi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared holder for MIDI mapping state that can be injected anywhere.
 * 
 * This is a singleton that holds the current mapping state, allowing both
 * MidiRouter (for event routing) and MidiViewModel (for UI) to share the same state.
 * 
 * This follows the proper DI pattern where shared state lives in a repository/holder,
 * not in a ViewModel that may have multiple instances.
 */
@SingleIn(AppScope::class)
@Inject
class MidiMappingStateHolder {
    
    private val _state = MutableStateFlow(MidiMappingState())
    val state: StateFlow<MidiMappingState> = _state.asStateFlow()
    
    // Learn mode is separate from mapping state so it can be toggled independently
    private val _isLearnModeActive = MutableStateFlow(false)
    val isLearnModeActive: StateFlow<Boolean> = _isLearnModeActive.asStateFlow()
    

    // ═══════════════════════════════════════════════════════════
    // STATE UPDATES
    // ═══════════════════════════════════════════════════════════
    
    fun updateState(newState: MidiMappingState) {
        _state.value = newState
    }
    
    fun updateState(transform: (MidiMappingState) -> MidiMappingState) {
        _state.update(transform)
    }
    
    fun setLearnModeActive(active: Boolean) {
        _isLearnModeActive.value = active
    }
    
    // ═══════════════════════════════════════════════════════════
    // MAPPING LOOKUPS (used by MidiRouter)
    // ═══════════════════════════════════════════════════════════
    
    fun getControlForCC(cc: Int): String? = _state.value.getControlForCC(cc)
    fun getVoiceForNote(note: Int): Int? = _state.value.getVoiceForNote(note)
    fun getControlForNote(note: Int): String? = _state.value.getControlForNote(note)
    
    // ═══════════════════════════════════════════════════════════
    // LEARN MODE (checking state for routing decisions)
    // ═══════════════════════════════════════════════════════════
    
    fun isInLearnMode(): Boolean = _isLearnModeActive.value
    fun getLearnTarget(): LearnTarget? = _state.value.learnTarget
    
    /**
     * Try to handle a CC event for learn mode.
     * Returns true if the event was consumed for learning, false otherwise.
     */
    fun tryLearnCC(cc: Int): Boolean {
        if (!_isLearnModeActive.value) return false
        
        val target = _state.value.learnTarget
        if (target !is LearnTarget.Control) return false
        
        // Assign the CC to the control and exit learn for this control
        _state.update { state ->
            state.assignCCToControl(cc, target.controlId)
        }
        return true
    }
    
    /**
     * Try to handle a note event for learn mode.
     * Returns true if the event was consumed for learning, false otherwise.
     */
    fun tryLearnNote(note: Int): Boolean {
        if (!_isLearnModeActive.value) return false
        
        val target = _state.value.learnTarget
        
        return when (target) {
            is LearnTarget.Voice -> {
                _state.update { state ->
                    state.assignNoteToVoice(note, target.index)
                }
                true
            }
            is LearnTarget.Control -> {
                _state.update { state ->
                    state.assignNoteToControl(note, target.controlId)
                }
                true
            }
            null -> false
        }
    }
}
