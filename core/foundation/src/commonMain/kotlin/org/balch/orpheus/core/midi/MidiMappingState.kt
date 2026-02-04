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
            const val HYPER_LFO_A_MULT = "hyper_lfo_a_mult"
            const val HYPER_LFO_B_MULT = "hyper_lfo_b_mult"

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
            fun quadVolume(index: Int) = "quad_${index}_volume"
            
            // Stereo controls
            const val STEREO_PAN = "stereo_pan"
            const val STEREO_MODE = "stereo_mode"
            
            // Structure
            const val FM_STRUCTURE = "fm_structure"
            
            // Evo controls
            const val EVO_DEPTH = "evo_depth"
            const val EVO_RATE = "evo_rate"
            const val EVO_VARIATION = "evo_variation"

            // Viz controls
            const val VIZ_KNOB_1 = "viz_knob_1"
            const val VIZ_KNOB_2 = "viz_knob_2"
            
            // Bender - pitch bend with spring-loaded behavior
            const val BENDER = "bender"
            
            // Resonator (Rings) controls
            const val RESONATOR_MODE = "resonator_mode"
            const val RESONATOR_STRUCTURE = "resonator_structure"
            const val RESONATOR_BRIGHTNESS = "resonator_brightness"
            const val RESONATOR_DAMPING = "resonator_damping"
            const val RESONATOR_POSITION = "resonator_position"
            const val RESONATOR_MIX = "resonator_mix"
            const val RESONATOR_TARGET_MIX = "resonator_target_mix"
            const val RESONATOR_SNAP_BACK = "resonator_snap_back"

            // Drum controls
            const val DRUM_BD_FREQ = "drum_bd_freq"
            const val DRUM_BD_TONE = "drum_bd_tone"
            const val DRUM_BD_DECAY = "drum_bd_decay"
            const val DRUM_BD_AFM = "drum_bd_afm"
            const val DRUM_BD_SFM = "drum_bd_sfm"
            const val DRUM_BD_TRIGGER = "drum_bd_trigger"
            const val DRUM_BD_TRIGGER_SOURCE = "drum_bd_trigger_source"
            const val DRUM_BD_PITCH_SOURCE = "drum_bd_pitch_source"

            const val DRUM_SD_FREQ = "drum_sd_freq"
            const val DRUM_SD_TONE = "drum_sd_tone"
            const val DRUM_SD_DECAY = "drum_sd_decay"
            const val DRUM_SD_SNAPPY = "drum_sd_snappy"
            const val DRUM_SD_TRIGGER = "drum_sd_trigger"
            const val DRUM_SD_TRIGGER_SOURCE = "drum_sd_trigger_source"
            const val DRUM_SD_PITCH_SOURCE = "drum_sd_pitch_source"

            const val DRUM_HH_FREQ = "drum_hh_freq"
            const val DRUM_HH_TONE = "drum_hh_tone"
            const val DRUM_HH_DECAY = "drum_hh_decay"
            const val DRUM_HH_NOISY = "drum_hh_noisy"
            const val DRUM_HH_TRIGGER = "drum_hh_trigger"
            const val DRUM_HH_TRIGGER_SOURCE = "drum_hh_trigger_source"
            const val DRUM_HH_PITCH_SOURCE = "drum_hh_pitch_source"

            // Beats/Sequencer controls
            const val BEATS_RUN = "beats_run"
            const val BEATS_X = "beats_x"
            const val BEATS_Y = "beats_y"
            const val BPM = "bpm"
            const val BEATS_MIX = "beats_mix"
            const val DRUMS_BYPASS = "drums_bypass"
            const val BEATS_RANDOMNESS = "beats_randomness"
            const val BEATS_SWING = "beats_swing"
            const val BEATS_MODE = "beats_mode"
            
            // Indexed Beats controls
            fun beatsDensity(index: Int) = "beats_density_${index + 1}" // 1-based in ID for consistency
            fun beatsEuclideanLength(index: Int) = "beats_euclidean_length_${index + 1}"
            
            // Warps (Meta-Modulator) controls
            const val WARPS_ALGORITHM = "warps_algorithm"
            const val WARPS_TIMBRE = "warps_timbre"
            const val WARPS_CARRIER_LEVEL = "warps_carrier_level"
            const val WARPS_MODULATOR_LEVEL = "warps_modulator_level"
            const val WARPS_CARRIER_SOURCE = "warps_carrier_source"
            const val WARPS_MODULATOR_SOURCE = "warps_modulator_source"
            const val WARPS_MIX = "warps_mix"

            // Grains (Clouds) controls
            const val GRAINS_POSITION = "grains_position"
            const val GRAINS_SIZE = "grains_size"
            const val GRAINS_PITCH = "grains_pitch"
            const val GRAINS_DENSITY = "grains_density"
            const val GRAINS_TEXTURE = "grains_texture"
            const val GRAINS_DRY_WET = "grains_dry_wet"
            const val GRAINS_FREEZE = "grains_freeze"
            const val GRAINS_MODE = "grains_mode"
            const val GRAINS_TRIGGER = "grains_trigger"

            // Flux controls
            const val FLUX_SPREAD = "flux_spread"
            const val FLUX_BIAS = "flux_bias"
            const val FLUX_STEPS = "flux_steps"
            const val FLUX_DEJA_VU = "flux_deja_vu"
            const val FLUX_LENGTH = "flux_length"
            const val FLUX_SCALE = "flux_scale"
            const val FLUX_RATE = "flux_rate"
            const val FLUX_JITTER = "flux_jitter"
            const val FLUX_PROBABILITY = "flux_probability"
            const val FLUX_CLOCK_SOURCE = "flux_clock_source"
            const val FLUX_GATE_LENGTH = "flux_gate_length"
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
