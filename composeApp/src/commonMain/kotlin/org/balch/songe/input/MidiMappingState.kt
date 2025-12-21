package org.balch.songe.input

import kotlinx.serialization.Serializable

/**
 * Target for MIDI learning - what control is waiting for MIDI input.
 */
@Serializable
sealed class LearnTarget {
    /** Learning a MIDI note → voice trigger mapping */
    @Serializable
    data class Voice(val index: Int) : LearnTarget()
    
    /** Learning a MIDI CC → control mapping */
    @Serializable
    data class Control(val controlId: String) : LearnTarget()
}

/**
 * Data class holding MIDI mappings for a device.
 * 
 * @param voiceMappings MIDI note number → voice index (0-7)
 * @param ccMappings MIDI CC number → control ID string
 * @param learnTarget What's currently being learned (null if not in learn mode)
 */
@Serializable
data class MidiMappingState(
    val voiceMappings: Map<Int, Int> = defaultMappings(),
    val ccMappings: Map<Int, String> = emptyMap(),
    val learnTarget: LearnTarget? = null
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
        
        // Control ID constants for CC mapping
        object ControlIds {
            // Voice controls (index 0-7)
            fun voiceTune(index: Int) = "voice_${index}_tune"
            fun voiceFmDepth(index: Int) = "voice_${index}_fm_depth"
            fun voiceEnvelopeSpeed(index: Int) = "voice_${index}_env_speed"
            fun voiceHold(index: Int) = "voice_${index}_hold"
            
            // Pair controls
            fun pairSharpness(pairIndex: Int) = "pair_${pairIndex}_sharpness"
            fun duoModSource(pairIndex: Int) = "pair_${pairIndex}_mod_source"
            
            // Delay controls
            const val DELAY_TIME_1 = "delay_time_1"
            const val DELAY_TIME_2 = "delay_time_2"
            const val DELAY_MOD_1 = "delay_mod_1"
            const val DELAY_MOD_2 = "delay_mod_2"
            const val DELAY_FEEDBACK = "delay_feedback"
            const val DELAY_MIX = "delay_mix"
            const val DELAY_MOD_SOURCE = "delay_mod_source" // SELF / LFO
            const val DELAY_LFO_WAVEFORM = "delay_lfo_waveform" // TRI / SQR
            
            // Hyper LFO
            const val HYPER_LFO_A = "hyper_lfo_a"
            const val HYPER_LFO_B = "hyper_lfo_b"
            const val HYPER_LFO_MODE = "hyper_lfo_mode"
            const val HYPER_LFO_LINK = "hyper_lfo_link"
            
            // Global
            const val MASTER_VOLUME = "master_volume"
            const val DRIVE = "drive"
            const val DISTORTION_MIX = "distortion_mix"
            const val VIBRATO = "vibrato"
            const val VOICE_COUPLING = "voice_coupling"
            const val TOTAL_FEEDBACK = "total_feedback"
            
            // Quad controls
            fun quadPitch(index: Int) = "quad_${index}_pitch"
            fun quadHold(index: Int) = "quad_${index}_hold"
        }
    }
    
    /**
     * Get the voice index for a MIDI note, or null if not mapped.
     */
    fun getVoiceForNote(midiNote: Int): Int? = voiceMappings[midiNote]
    
    /**
     * Get the control ID for a MIDI CC, or null if not mapped.
     */
    fun getControlForCC(ccNumber: Int): String? = ccMappings[ccNumber]
    
    /**
     * Check if we're currently learning.
     */
    val isLearning: Boolean get() = learnTarget != null
    
    /**
     * Check if a specific control is being learned.
     */
    fun isLearningControl(controlId: String): Boolean = 
        (learnTarget as? LearnTarget.Control)?.controlId == controlId
    
    /**
     * Check if a specific voice is being learned.
     */
    fun isLearningVoice(voiceIndex: Int): Boolean =
        (learnTarget as? LearnTarget.Voice)?.index == voiceIndex
    
    /**
     * Assign a MIDI note to a voice.
     */
    fun assignNoteToVoice(midiNote: Int, voiceIndex: Int): MidiMappingState {
        // Remove any existing mapping to this voice
        val newMappings = voiceMappings.filterValues { it != voiceIndex }.toMutableMap()
        newMappings[midiNote] = voiceIndex
        return copy(voiceMappings = newMappings, learnTarget = null)
    }
    
    /**
     * Assign a MIDI CC to a control.
     */
    fun assignCCToControl(ccNumber: Int, controlId: String): MidiMappingState {
        // Remove any existing mapping to this control
        val newMappings = ccMappings.filterValues { it != controlId }.toMutableMap()
        newMappings[ccNumber] = controlId
        return copy(ccMappings = newMappings, learnTarget = null)
    }
    
    /**
     * Start learn mode for a voice.
     */
    fun startLearnVoice(voiceIndex: Int): MidiMappingState = 
        copy(learnTarget = LearnTarget.Voice(voiceIndex))
    
    /**
     * Start learn mode for a control.
     */
    fun startLearnControl(controlId: String): MidiMappingState = 
        copy(learnTarget = LearnTarget.Control(controlId))
    
    /**
     * Cancel learn mode.
     */
    fun cancelLearn(): MidiMappingState = copy(learnTarget = null)
    
    /**
     * Reset to default mappings.
     */
    fun reset(): MidiMappingState = MidiMappingState()
    
    /**
     * Create a copy without the transient learn state (for persistence).
     */
    fun forPersistence(): MidiMappingState = copy(learnTarget = null)
}
