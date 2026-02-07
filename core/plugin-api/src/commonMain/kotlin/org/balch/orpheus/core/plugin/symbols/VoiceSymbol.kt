package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val VOICE_URI = "org.balch.orpheus.plugins.voice"

/**
 * Exhaustive enum of all Voice plugin port symbols.
 *
 * Indexed ports use suffixed symbols (e.g. "tune_0" through "tune_11").
 * Use the companion helpers to get the correct symbol for a given index.
 */
enum class VoiceSymbol(
    override val symbol: Symbol,
    override val uri: String = VOICE_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    // Per-voice (×12)
    TUNE_0("tune_0", displayName = "Tune 0"),
    TUNE_1("tune_1", displayName = "Tune 1"),
    TUNE_2("tune_2", displayName = "Tune 2"),
    TUNE_3("tune_3", displayName = "Tune 3"),
    TUNE_4("tune_4", displayName = "Tune 4"),
    TUNE_5("tune_5", displayName = "Tune 5"),
    TUNE_6("tune_6", displayName = "Tune 6"),
    TUNE_7("tune_7", displayName = "Tune 7"),
    TUNE_8("tune_8", displayName = "Tune 8"),
    TUNE_9("tune_9", displayName = "Tune 9"),
    TUNE_10("tune_10", displayName = "Tune 10"),
    TUNE_11("tune_11", displayName = "Tune 11"),

    MOD_DEPTH_0("mod_depth_0", displayName = "Mod Depth 0"),
    MOD_DEPTH_1("mod_depth_1", displayName = "Mod Depth 1"),
    MOD_DEPTH_2("mod_depth_2", displayName = "Mod Depth 2"),
    MOD_DEPTH_3("mod_depth_3", displayName = "Mod Depth 3"),
    MOD_DEPTH_4("mod_depth_4", displayName = "Mod Depth 4"),
    MOD_DEPTH_5("mod_depth_5", displayName = "Mod Depth 5"),
    MOD_DEPTH_6("mod_depth_6", displayName = "Mod Depth 6"),
    MOD_DEPTH_7("mod_depth_7", displayName = "Mod Depth 7"),
    MOD_DEPTH_8("mod_depth_8", displayName = "Mod Depth 8"),
    MOD_DEPTH_9("mod_depth_9", displayName = "Mod Depth 9"),
    MOD_DEPTH_10("mod_depth_10", displayName = "Mod Depth 10"),
    MOD_DEPTH_11("mod_depth_11", displayName = "Mod Depth 11"),

    ENV_SPEED_0("env_speed_0", displayName = "Env Speed 0"),
    ENV_SPEED_1("env_speed_1", displayName = "Env Speed 1"),
    ENV_SPEED_2("env_speed_2", displayName = "Env Speed 2"),
    ENV_SPEED_3("env_speed_3", displayName = "Env Speed 3"),
    ENV_SPEED_4("env_speed_4", displayName = "Env Speed 4"),
    ENV_SPEED_5("env_speed_5", displayName = "Env Speed 5"),
    ENV_SPEED_6("env_speed_6", displayName = "Env Speed 6"),
    ENV_SPEED_7("env_speed_7", displayName = "Env Speed 7"),
    ENV_SPEED_8("env_speed_8", displayName = "Env Speed 8"),
    ENV_SPEED_9("env_speed_9", displayName = "Env Speed 9"),
    ENV_SPEED_10("env_speed_10", displayName = "Env Speed 10"),
    ENV_SPEED_11("env_speed_11", displayName = "Env Speed 11"),

    // Per-pair (×6)
    PAIR_SHARPNESS_0("pair_sharpness_0", displayName = "Pair Sharpness 0"),
    PAIR_SHARPNESS_1("pair_sharpness_1", displayName = "Pair Sharpness 1"),
    PAIR_SHARPNESS_2("pair_sharpness_2", displayName = "Pair Sharpness 2"),
    PAIR_SHARPNESS_3("pair_sharpness_3", displayName = "Pair Sharpness 3"),
    PAIR_SHARPNESS_4("pair_sharpness_4", displayName = "Pair Sharpness 4"),
    PAIR_SHARPNESS_5("pair_sharpness_5", displayName = "Pair Sharpness 5"),

    DUO_MOD_SOURCE_0("duo_mod_source_0", displayName = "Duo Mod Source 0"),
    DUO_MOD_SOURCE_1("duo_mod_source_1", displayName = "Duo Mod Source 1"),
    DUO_MOD_SOURCE_2("duo_mod_source_2", displayName = "Duo Mod Source 2"),
    DUO_MOD_SOURCE_3("duo_mod_source_3", displayName = "Duo Mod Source 3"),
    DUO_MOD_SOURCE_4("duo_mod_source_4", displayName = "Duo Mod Source 4"),
    DUO_MOD_SOURCE_5("duo_mod_source_5", displayName = "Duo Mod Source 5"),

    // Per-quad (×3)
    QUAD_PITCH_0("quad_pitch_0", displayName = "Quad Pitch 0"),
    QUAD_PITCH_1("quad_pitch_1", displayName = "Quad Pitch 1"),
    QUAD_PITCH_2("quad_pitch_2", displayName = "Quad Pitch 2"),

    QUAD_HOLD_0("quad_hold_0", displayName = "Quad Hold 0"),
    QUAD_HOLD_1("quad_hold_1", displayName = "Quad Hold 1"),
    QUAD_HOLD_2("quad_hold_2", displayName = "Quad Hold 2"),

    QUAD_VOLUME_0("quad_volume_0", displayName = "Quad Volume 0"),
    QUAD_VOLUME_1("quad_volume_1", displayName = "Quad Volume 1"),
    QUAD_VOLUME_2("quad_volume_2", displayName = "Quad Volume 2"),

    QUAD_TRIGGER_SOURCE_0("quad_trigger_source_0", displayName = "Quad Trigger Source 0"),
    QUAD_TRIGGER_SOURCE_1("quad_trigger_source_1", displayName = "Quad Trigger Source 1"),
    QUAD_TRIGGER_SOURCE_2("quad_trigger_source_2", displayName = "Quad Trigger Source 2"),

    QUAD_PITCH_SOURCE_0("quad_pitch_source_0", displayName = "Quad Pitch Source 0"),
    QUAD_PITCH_SOURCE_1("quad_pitch_source_1", displayName = "Quad Pitch Source 1"),
    QUAD_PITCH_SOURCE_2("quad_pitch_source_2", displayName = "Quad Pitch Source 2"),

    QUAD_ENV_TRIGGER_MODE_0("quad_env_trigger_mode_0", displayName = "Quad Env Trigger Mode 0"),
    QUAD_ENV_TRIGGER_MODE_1("quad_env_trigger_mode_1", displayName = "Quad Env Trigger Mode 1"),
    QUAD_ENV_TRIGGER_MODE_2("quad_env_trigger_mode_2", displayName = "Quad Env Trigger Mode 2"),

    // Global
    FM_STRUCTURE_CROSS_QUAD("fm_structure_cross_quad", displayName = "FM Cross Quad"),
    TOTAL_FEEDBACK("total_feedback", displayName = "Total Feedback"),
    VIBRATO("vibrato", displayName = "Vibrato"),
    COUPLING("coupling", displayName = "Voice Coupling");

    companion object {
        private val tunes = arrayOf(TUNE_0, TUNE_1, TUNE_2, TUNE_3, TUNE_4, TUNE_5, TUNE_6, TUNE_7, TUNE_8, TUNE_9, TUNE_10, TUNE_11)
        private val modDepths = arrayOf(MOD_DEPTH_0, MOD_DEPTH_1, MOD_DEPTH_2, MOD_DEPTH_3, MOD_DEPTH_4, MOD_DEPTH_5, MOD_DEPTH_6, MOD_DEPTH_7, MOD_DEPTH_8, MOD_DEPTH_9, MOD_DEPTH_10, MOD_DEPTH_11)
        private val envSpeeds = arrayOf(ENV_SPEED_0, ENV_SPEED_1, ENV_SPEED_2, ENV_SPEED_3, ENV_SPEED_4, ENV_SPEED_5, ENV_SPEED_6, ENV_SPEED_7, ENV_SPEED_8, ENV_SPEED_9, ENV_SPEED_10, ENV_SPEED_11)
        private val pairSharpnesses = arrayOf(PAIR_SHARPNESS_0, PAIR_SHARPNESS_1, PAIR_SHARPNESS_2, PAIR_SHARPNESS_3, PAIR_SHARPNESS_4, PAIR_SHARPNESS_5)
        private val duoModSources = arrayOf(DUO_MOD_SOURCE_0, DUO_MOD_SOURCE_1, DUO_MOD_SOURCE_2, DUO_MOD_SOURCE_3, DUO_MOD_SOURCE_4, DUO_MOD_SOURCE_5)
        private val quadPitches = arrayOf(QUAD_PITCH_0, QUAD_PITCH_1, QUAD_PITCH_2)
        private val quadHolds = arrayOf(QUAD_HOLD_0, QUAD_HOLD_1, QUAD_HOLD_2)
        private val quadVolumes = arrayOf(QUAD_VOLUME_0, QUAD_VOLUME_1, QUAD_VOLUME_2)
        private val quadTriggerSources = arrayOf(QUAD_TRIGGER_SOURCE_0, QUAD_TRIGGER_SOURCE_1, QUAD_TRIGGER_SOURCE_2)
        private val quadPitchSources = arrayOf(QUAD_PITCH_SOURCE_0, QUAD_PITCH_SOURCE_1, QUAD_PITCH_SOURCE_2)
        private val quadEnvTriggerModes = arrayOf(QUAD_ENV_TRIGGER_MODE_0, QUAD_ENV_TRIGGER_MODE_1, QUAD_ENV_TRIGGER_MODE_2)

        fun tune(index: Int): VoiceSymbol = tunes[index]
        fun modDepth(index: Int): VoiceSymbol = modDepths[index]
        fun envSpeed(index: Int): VoiceSymbol = envSpeeds[index]
        fun pairSharpness(index: Int): VoiceSymbol = pairSharpnesses[index]
        fun duoModSource(index: Int): VoiceSymbol = duoModSources[index]
        fun quadPitch(index: Int): VoiceSymbol = quadPitches[index]
        fun quadHold(index: Int): VoiceSymbol = quadHolds[index]
        fun quadVolume(index: Int): VoiceSymbol = quadVolumes[index]
        fun quadTriggerSource(index: Int): VoiceSymbol = quadTriggerSources[index]
        fun quadPitchSource(index: Int): VoiceSymbol = quadPitchSources[index]
        fun quadEnvTriggerMode(index: Int): VoiceSymbol = quadEnvTriggerModes[index]
    }
}
