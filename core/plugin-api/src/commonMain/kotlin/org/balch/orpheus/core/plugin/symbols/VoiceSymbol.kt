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
    DUO_SHARPNESS_0("duo_sharpness_0", displayName = "Duo Sharpness 0"),
    DUO_SHARPNESS_1("duo_sharpness_1", displayName = "Duo Sharpness 1"),
    DUO_SHARPNESS_2("duo_sharpness_2", displayName = "Duo Sharpness 2"),
    DUO_SHARPNESS_3("duo_sharpness_3", displayName = "Duo Sharpness 3"),
    DUO_SHARPNESS_4("duo_sharpness_4", displayName = "Duo Sharpness 4"),
    DUO_SHARPNESS_5("duo_sharpness_5", displayName = "Duo Sharpness 5"),

    DUO_MOD_SOURCE_0("duo_mod_source_0", displayName = "Duo Mod Source 0"),
    DUO_MOD_SOURCE_1("duo_mod_source_1", displayName = "Duo Mod Source 1"),
    DUO_MOD_SOURCE_2("duo_mod_source_2", displayName = "Duo Mod Source 2"),
    DUO_MOD_SOURCE_3("duo_mod_source_3", displayName = "Duo Mod Source 3"),
    DUO_MOD_SOURCE_4("duo_mod_source_4", displayName = "Duo Mod Source 4"),
    DUO_MOD_SOURCE_5("duo_mod_source_5", displayName = "Duo Mod Source 5"),

    DUO_ENGINE_0("duo_engine_0", displayName = "Duo Engine 0"),
    DUO_ENGINE_1("duo_engine_1", displayName = "Duo Engine 1"),
    DUO_ENGINE_2("duo_engine_2", displayName = "Duo Engine 2"),
    DUO_ENGINE_3("duo_engine_3", displayName = "Duo Engine 3"),
    DUO_ENGINE_4("duo_engine_4", displayName = "Duo Engine 4"),
    DUO_ENGINE_5("duo_engine_5", displayName = "Duo Engine 5"),

    DUO_HARMONICS_0("duo_harmonics_0", displayName = "Duo Harmonics 0"),
    DUO_HARMONICS_1("duo_harmonics_1", displayName = "Duo Harmonics 1"),
    DUO_HARMONICS_2("duo_harmonics_2", displayName = "Duo Harmonics 2"),
    DUO_HARMONICS_3("duo_harmonics_3", displayName = "Duo Harmonics 3"),
    DUO_HARMONICS_4("duo_harmonics_4", displayName = "Duo Harmonics 4"),
    DUO_HARMONICS_5("duo_harmonics_5", displayName = "Duo Harmonics 5"),

    DUO_PROSODY_0("duo_prosody_0", displayName = "Duo Prosody 0"),
    DUO_PROSODY_1("duo_prosody_1", displayName = "Duo Prosody 1"),
    DUO_PROSODY_2("duo_prosody_2", displayName = "Duo Prosody 2"),
    DUO_PROSODY_3("duo_prosody_3", displayName = "Duo Prosody 3"),
    DUO_PROSODY_4("duo_prosody_4", displayName = "Duo Prosody 4"),
    DUO_PROSODY_5("duo_prosody_5", displayName = "Duo Prosody 5"),

    DUO_SPEED_0("duo_speed_0", displayName = "Duo Speed 0"),
    DUO_SPEED_1("duo_speed_1", displayName = "Duo Speed 1"),
    DUO_SPEED_2("duo_speed_2", displayName = "Duo Speed 2"),
    DUO_SPEED_3("duo_speed_3", displayName = "Duo Speed 3"),
    DUO_SPEED_4("duo_speed_4", displayName = "Duo Speed 4"),
    DUO_SPEED_5("duo_speed_5", displayName = "Duo Speed 5"),

    DUO_MORPH_0("duo_morph_0", displayName = "Duo Morph 0"),
    DUO_MORPH_1("duo_morph_1", displayName = "Duo Morph 1"),
    DUO_MORPH_2("duo_morph_2", displayName = "Duo Morph 2"),
    DUO_MORPH_3("duo_morph_3", displayName = "Duo Morph 3"),
    DUO_MORPH_4("duo_morph_4", displayName = "Duo Morph 4"),
    DUO_MORPH_5("duo_morph_5", displayName = "Duo Morph 5"),

    DUO_MOD_SOURCE_LEVEL_0("duo_mod_source_level_0", displayName = "Duo Mod Source Level 0"),
    DUO_MOD_SOURCE_LEVEL_1("duo_mod_source_level_1", displayName = "Duo Mod Source Level 1"),
    DUO_MOD_SOURCE_LEVEL_2("duo_mod_source_level_2", displayName = "Duo Mod Source Level 2"),
    DUO_MOD_SOURCE_LEVEL_3("duo_mod_source_level_3", displayName = "Duo Mod Source Level 3"),
    DUO_MOD_SOURCE_LEVEL_4("duo_mod_source_level_4", displayName = "Duo Mod Source Level 4"),
    DUO_MOD_SOURCE_LEVEL_5("duo_mod_source_level_5", displayName = "Duo Mod Source Level 5"),

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
        private val duoSharpnesses = arrayOf(DUO_SHARPNESS_0, DUO_SHARPNESS_1, DUO_SHARPNESS_2, DUO_SHARPNESS_3, DUO_SHARPNESS_4, DUO_SHARPNESS_5)
        private val duoModSources = arrayOf(DUO_MOD_SOURCE_0, DUO_MOD_SOURCE_1, DUO_MOD_SOURCE_2, DUO_MOD_SOURCE_3, DUO_MOD_SOURCE_4, DUO_MOD_SOURCE_5)
        private val duoEngines = arrayOf(DUO_ENGINE_0, DUO_ENGINE_1, DUO_ENGINE_2, DUO_ENGINE_3, DUO_ENGINE_4, DUO_ENGINE_5)
        private val duoHarmonics = arrayOf(DUO_HARMONICS_0, DUO_HARMONICS_1, DUO_HARMONICS_2, DUO_HARMONICS_3, DUO_HARMONICS_4, DUO_HARMONICS_5)
        private val duoProsodies = arrayOf(DUO_PROSODY_0, DUO_PROSODY_1, DUO_PROSODY_2, DUO_PROSODY_3, DUO_PROSODY_4, DUO_PROSODY_5)
        private val duoSpeeds = arrayOf(DUO_SPEED_0, DUO_SPEED_1, DUO_SPEED_2, DUO_SPEED_3, DUO_SPEED_4, DUO_SPEED_5)
        private val duoMorphs = arrayOf(DUO_MORPH_0, DUO_MORPH_1, DUO_MORPH_2, DUO_MORPH_3, DUO_MORPH_4, DUO_MORPH_5)
        private val duoModSourceLevels = arrayOf(DUO_MOD_SOURCE_LEVEL_0, DUO_MOD_SOURCE_LEVEL_1, DUO_MOD_SOURCE_LEVEL_2, DUO_MOD_SOURCE_LEVEL_3, DUO_MOD_SOURCE_LEVEL_4, DUO_MOD_SOURCE_LEVEL_5)
        private val quadPitches = arrayOf(QUAD_PITCH_0, QUAD_PITCH_1, QUAD_PITCH_2)
        private val quadHolds = arrayOf(QUAD_HOLD_0, QUAD_HOLD_1, QUAD_HOLD_2)
        private val quadVolumes = arrayOf(QUAD_VOLUME_0, QUAD_VOLUME_1, QUAD_VOLUME_2)
        private val quadTriggerSources = arrayOf(QUAD_TRIGGER_SOURCE_0, QUAD_TRIGGER_SOURCE_1, QUAD_TRIGGER_SOURCE_2)
        private val quadPitchSources = arrayOf(QUAD_PITCH_SOURCE_0, QUAD_PITCH_SOURCE_1, QUAD_PITCH_SOURCE_2)
        private val quadEnvTriggerModes = arrayOf(QUAD_ENV_TRIGGER_MODE_0, QUAD_ENV_TRIGGER_MODE_1, QUAD_ENV_TRIGGER_MODE_2)

        fun tune(index: Int): VoiceSymbol = tunes[index]
        fun modDepth(index: Int): VoiceSymbol = modDepths[index]
        fun envSpeed(index: Int): VoiceSymbol = envSpeeds[index]
        fun duoSharpness(index: Int): VoiceSymbol = duoSharpnesses[index]
        fun duoModSource(index: Int): VoiceSymbol = duoModSources[index]
        fun duoEngine(index: Int): VoiceSymbol = duoEngines[index]
        fun duoHarmonics(index: Int): VoiceSymbol = duoHarmonics[index]
        fun duoProsody(index: Int): VoiceSymbol = duoProsodies[index]
        fun duoSpeed(index: Int): VoiceSymbol = duoSpeeds[index]
        fun duoMorph(index: Int): VoiceSymbol = duoMorphs[index]
        fun duoModSourceLevel(index: Int): VoiceSymbol = duoModSourceLevels[index]
        fun quadPitch(index: Int): VoiceSymbol = quadPitches[index]
        fun quadHold(index: Int): VoiceSymbol = quadHolds[index]
        fun quadVolume(index: Int): VoiceSymbol = quadVolumes[index]
        fun quadTriggerSource(index: Int): VoiceSymbol = quadTriggerSources[index]
        fun quadPitchSource(index: Int): VoiceSymbol = quadPitchSources[index]
        fun quadEnvTriggerMode(index: Int): VoiceSymbol = quadEnvTriggerModes[index]
    }
}
