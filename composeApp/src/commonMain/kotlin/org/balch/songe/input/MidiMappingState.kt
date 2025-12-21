package org.balch.songe.input

/**
 * Data class holding custom MIDI note-to-voice mappings.
 * 
 * @param voiceMappings Map of MIDI note number → voice index (0-7)
 * @param learnMode Which voice is currently learning (0-7), null if not learning
 */
data class MidiMappingState(
    val voiceMappings: Map<Int, Int> = defaultMappings(),
    val learnMode: Int? = null
) {
    companion object {
        /**
         * Default mapping: C4-G4 (MIDI notes 60-67) → Voices 1-8
         */
        fun defaultMappings(): Map<Int, Int> = (60..67).mapIndexed { index, note -> 
            note to index 
        }.toMap()
        
        /**
         * Get note name for a MIDI note number.
         */
        fun noteName(midiNote: Int): String {
            val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            val octave = (midiNote / 12) - 1
            val note = noteNames[midiNote % 12]
            return "$note$octave"
        }
    }
    
    /**
     * Get the voice index for a MIDI note, or null if not mapped.
     */
    fun getVoiceForNote(midiNote: Int): Int? = voiceMappings[midiNote]
    
    /**
     * Assign a MIDI note to a voice (for learn mode).
     */
    fun assignNote(midiNote: Int, voiceIndex: Int): MidiMappingState {
        // Remove any existing mapping to this voice
        val newMappings = voiceMappings.filterValues { it != voiceIndex }.toMutableMap()
        newMappings[midiNote] = voiceIndex
        return copy(voiceMappings = newMappings, learnMode = null)
    }
    
    /**
     * Start learn mode for a voice.
     */
    fun startLearn(voiceIndex: Int): MidiMappingState = copy(learnMode = voiceIndex)
    
    /**
     * Cancel learn mode.
     */
    fun cancelLearn(): MidiMappingState = copy(learnMode = null)
    
    /**
     * Reset to default mappings.
     */
    fun reset(): MidiMappingState = MidiMappingState()
}
