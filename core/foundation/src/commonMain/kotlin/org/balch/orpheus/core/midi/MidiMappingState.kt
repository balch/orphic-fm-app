package org.balch.orpheus.core.midi

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
 * @param noteControlMappings MIDI note number → control ID string (for button toggles)
 * @param learnTarget What's currently being learned (null if not in learn mode)
 */
@Serializable
data class MidiMappingState(
    val voiceMappings: Map<Int, Int> = defaultMappings(),
    val ccMappings: Map<Int, String> = emptyMap(),
    val noteControlMappings: Map<Int, String> = emptyMap(),
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
     * Get the control ID for a MIDI Note, or null if not mapped.
     */
    fun getControlForNote(note: Int): String? = noteControlMappings[note]

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

        // Remove conflicting Control mapping (if we want exclusive mapping per note)
        val newNoteControlMappings = noteControlMappings.minus(midiNote)

        return copy(
            voiceMappings = newMappings,
            noteControlMappings = newNoteControlMappings,
            learnTarget = null
        )
    }

    /**
     * Assign a MIDI Note to a control.
     */
    fun assignNoteToControl(note: Int, controlId: String): MidiMappingState {
        // Remove any existing note mapping to this control
        val newMappings = noteControlMappings.filterValues { it != controlId }.toMutableMap()
        newMappings[note] = controlId

        // Remove conflicting Voice mapping
        val newVoiceMappings = voiceMappings.minus(note)

        return copy(
            noteControlMappings = newMappings,
            voiceMappings = newVoiceMappings,
            learnTarget = null
        )
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
