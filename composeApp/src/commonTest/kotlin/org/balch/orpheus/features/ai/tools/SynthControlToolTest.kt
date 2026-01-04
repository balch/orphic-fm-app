package org.balch.orpheus.features.ai.tools

import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.routing.SynthController
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unit tests for the SynthControlTool.
 *
 * Tests that all control IDs are correctly parsed and mapped to the synth engine.
 */
class SynthControlToolTest {

    private val synthController = SynthController()
    private val tool = SynthControlTool(synthController)

    // =========================================================================
    // VOICE_TUNE Tests
    // =========================================================================

    @Test
    fun `VOICE_TUNE_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VOICE_TUNE_1", 0.5f))
        assertTrue(result.success, "VOICE_TUNE_1 should succeed")
        assertTrue(result.message.contains("voice_0_tune"), "Should map to voice_0_tune")
    }

    @Test
    fun `VOICE_TUNE_8 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VOICE_TUNE_8", 0.6f))
        assertTrue(result.success, "VOICE_TUNE_8 should succeed")
        assertTrue(result.message.contains("voice_7_tune"), "Should map to voice_7_tune")
    }

    @Test
    fun `VOICE_TUNE_12 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VOICE_TUNE_12", 0.7f))
        assertTrue(result.success, "VOICE_TUNE_12 should succeed")
        assertTrue(result.message.contains("voice_11_tune"), "Should map to voice_11_tune")
    }

    // =========================================================================
    // VOICE_FM_DEPTH Tests
    // =========================================================================

    @Test
    fun `VOICE_FM_DEPTH_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VOICE_FM_DEPTH_1", 0.3f))
        assertTrue(result.success, "VOICE_FM_DEPTH_1 should succeed")
        assertTrue(result.message.contains("voice_0_fm_depth"), "Should map to voice_0_fm_depth")
    }

    @Test
    fun `VOICE_FM_DEPTH_8 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VOICE_FM_DEPTH_8", 0.5f))
        assertTrue(result.success, "VOICE_FM_DEPTH_8 should succeed")
        assertTrue(result.message.contains("voice_7_fm_depth"), "Should map to voice_7_fm_depth")
    }

    // =========================================================================
    // VOICE_ENV_SPEED Tests
    // =========================================================================

    @Test
    fun `VOICE_ENV_SPEED_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VOICE_ENV_SPEED_1", 0.8f))
        assertTrue(result.success, "VOICE_ENV_SPEED_1 should succeed")
        assertTrue(result.message.contains("voice_0_env_speed"), "Should map to voice_0_env_speed")
    }

    @Test
    fun `VOICE_ENV_SPEED_8 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VOICE_ENV_SPEED_8", 0.9f))
        assertTrue(result.success, "VOICE_ENV_SPEED_8 should succeed")
        assertTrue(result.message.contains("voice_7_env_speed"), "Should map to voice_7_env_speed")
    }

    // =========================================================================
    // QUAD Controls Tests
    // =========================================================================

    @Test
    fun `QUAD_PITCH_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("QUAD_PITCH_1", 0.5f))
        assertTrue(result.success, "QUAD_PITCH_1 should succeed")
        assertTrue(result.message.contains("quad_0_pitch"), "Should map to quad_0_pitch")
    }

    @Test
    fun `QUAD_PITCH_2 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("QUAD_PITCH_2", 0.6f))
        assertTrue(result.success, "QUAD_PITCH_2 should succeed")
        assertTrue(result.message.contains("quad_1_pitch"), "Should map to quad_1_pitch")
    }

    @Test
    fun `QUAD_PITCH_3 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("QUAD_PITCH_3", 0.7f))
        assertTrue(result.success, "QUAD_PITCH_3 should succeed")
        assertTrue(result.message.contains("quad_2_pitch"), "Should map to quad_2_pitch")
    }

    @Test
    fun `QUAD_HOLD_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("QUAD_HOLD_1", 0.8f))
        assertTrue(result.success, "QUAD_HOLD_1 should succeed")
        assertTrue(result.message.contains("quad_0_hold"), "Should map to quad_0_hold")
    }

    @Test
    fun `QUAD_HOLD_2 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("QUAD_HOLD_2", 0.9f))
        assertTrue(result.success, "QUAD_HOLD_2 should succeed")
        assertTrue(result.message.contains("quad_1_hold"), "Should map to quad_1_hold")
    }

    @Test
    fun `QUAD_HOLD_3 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("QUAD_HOLD_3", 0.5f))
        assertTrue(result.success, "QUAD_HOLD_3 should succeed")
        assertTrue(result.message.contains("quad_2_hold"), "Should map to quad_2_hold")
    }

    @Test
    fun `QUAD_VOLUME_3 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("QUAD_VOLUME_3", 0.3f))
        assertTrue(result.success, "QUAD_VOLUME_3 should succeed")
        assertTrue(result.message.contains("quad_2_volume"), "Should map to quad_2_volume")
    }

    // =========================================================================
    // DUO_MOD_SOURCE Tests
    // =========================================================================

    @Test
    fun `DUO_MOD_SOURCE_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DUO_MOD_SOURCE_1", 0.0f))
        assertTrue(result.success, "DUO_MOD_SOURCE_1 should succeed")
        assertTrue(result.message.contains("pair_0_mod_source"), "Should map to pair_0_mod_source")
    }

    @Test
    fun `DUO_MOD_SOURCE_4 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DUO_MOD_SOURCE_4", 1.0f))
        assertTrue(result.success, "DUO_MOD_SOURCE_4 should succeed")
        assertTrue(result.message.contains("pair_3_mod_source"), "Should map to pair_3_mod_source")
    }

    // =========================================================================
    // PAIR_SHARPNESS Tests  
    // =========================================================================

    @Test
    fun `PAIR_SHARPNESS_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("PAIR_SHARPNESS_1", 0.5f))
        assertTrue(result.success, "PAIR_SHARPNESS_1 should succeed")
        assertTrue(result.message.contains("pair_0_sharpness"), "Should map to pair_0_sharpness")
    }

    @Test
    fun `PAIR_SHARPNESS_4 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("PAIR_SHARPNESS_4", 0.8f))
        assertTrue(result.success, "PAIR_SHARPNESS_4 should succeed")
        assertTrue(result.message.contains("pair_3_sharpness"), "Should map to pair_3_sharpness")
    }

    // =========================================================================
    // LFO Controls Tests
    // =========================================================================

    @Test
    fun `HYPER_LFO_A parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("HYPER_LFO_A", 0.5f))
        assertTrue(result.success, "HYPER_LFO_A should succeed")
        assertTrue(result.message.contains("hyper_lfo_a"), "Should map to hyper_lfo_a")
    }

    @Test
    fun `HYPER_LFO_B parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("HYPER_LFO_B", 0.6f))
        assertTrue(result.success, "HYPER_LFO_B should succeed")
        assertTrue(result.message.contains("hyper_lfo_b"), "Should map to hyper_lfo_b")
    }

    @Test
    fun `HYPER_LFO_MODE parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("HYPER_LFO_MODE", 0.5f))
        assertTrue(result.success, "HYPER_LFO_MODE should succeed")
        assertTrue(result.message.contains("hyper_lfo_mode"), "Should map to hyper_lfo_mode")
    }

    @Test
    fun `HYPER_LFO_LINK parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("HYPER_LFO_LINK", 1.0f))
        assertTrue(result.success, "HYPER_LFO_LINK should succeed")
        assertTrue(result.message.contains("hyper_lfo_link"), "Should map to hyper_lfo_link")
    }

    // =========================================================================
    // Delay Controls Tests
    // =========================================================================

    @Test
    fun `DELAY_TIME_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DELAY_TIME_1", 0.5f))
        assertTrue(result.success, "DELAY_TIME_1 should succeed")
        assertTrue(result.message.contains("delay_time_1"), "Should map to delay_time_1")
    }

    @Test
    fun `DELAY_TIME_2 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DELAY_TIME_2", 0.6f))
        assertTrue(result.success, "DELAY_TIME_2 should succeed")
        assertTrue(result.message.contains("delay_time_2"), "Should map to delay_time_2")
    }

    @Test
    fun `DELAY_MOD_1 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DELAY_MOD_1", 0.3f))
        assertTrue(result.success, "DELAY_MOD_1 should succeed")
        assertTrue(result.message.contains("delay_mod_1"), "Should map to delay_mod_1")
    }

    @Test
    fun `DELAY_MOD_2 parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DELAY_MOD_2", 0.4f))
        assertTrue(result.success, "DELAY_MOD_2 should succeed")
        assertTrue(result.message.contains("delay_mod_2"), "Should map to delay_mod_2")
    }

    @Test
    fun `DELAY_FEEDBACK parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DELAY_FEEDBACK", 0.7f))
        assertTrue(result.success, "DELAY_FEEDBACK should succeed")
        assertTrue(result.message.contains("delay_feedback"), "Should map to delay_feedback")
    }

    @Test
    fun `DELAY_MIX parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DELAY_MIX", 0.5f))
        assertTrue(result.success, "DELAY_MIX should succeed")
        assertTrue(result.message.contains("delay_mix"), "Should map to delay_mix")
    }

    @Test
    fun `DELAY_MOD_SOURCE parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DELAY_MOD_SOURCE", 1.0f))
        assertTrue(result.success, "DELAY_MOD_SOURCE should succeed")
        assertTrue(result.message.contains("delay_mod_source"), "Should map to delay_mod_source")
    }

    @Test
    fun `DELAY_LFO_WAVEFORM parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DELAY_LFO_WAVEFORM", 0.0f))
        assertTrue(result.success, "DELAY_LFO_WAVEFORM should succeed")
        assertTrue(result.message.contains("delay_lfo_waveform"), "Should map to delay_lfo_waveform")
    }

    // =========================================================================
    // Global Effects Tests
    // =========================================================================

    @Test
    fun `VIBRATO parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VIBRATO", 0.4f))
        assertTrue(result.success, "VIBRATO should succeed")
        assertTrue(result.message.contains("vibrato"), "Should map to vibrato")
    }

    @Test
    fun `DRIVE parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DRIVE", 0.5f))
        assertTrue(result.success, "DRIVE should succeed")
        assertTrue(result.message.contains("drive"), "Should map to drive")
    }

    @Test
    fun `DISTORTION_MIX parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("DISTORTION_MIX", 0.3f))
        assertTrue(result.success, "DISTORTION_MIX should succeed")
        assertTrue(result.message.contains("distortion_mix"), "Should map to distortion_mix")
    }

    @Test
    fun `VOICE_COUPLING parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VOICE_COUPLING", 0.2f))
        assertTrue(result.success, "VOICE_COUPLING should succeed")
        assertTrue(result.message.contains("voice_coupling"), "Should map to voice_coupling")
    }

    @Test
    fun `TOTAL_FEEDBACK parses and executes successfully`() = runTest {
        val result = tool.execute(SynthControlTool.Args("TOTAL_FEEDBACK", 0.1f))
        assertTrue(result.success, "TOTAL_FEEDBACK should succeed")
        assertTrue(result.message.contains("total_feedback"), "Should map to total_feedback")
    }

    // =========================================================================
    // BENDER Tests (special -1.0 to +1.0 range)
    // =========================================================================

    @Test
    fun `BENDER parses and executes at center`() = runTest {
        val result = tool.execute(SynthControlTool.Args("BENDER", 0.0f))
        assertTrue(result.success, "BENDER at 0.0 should succeed")
        assertTrue(result.message.contains("center"), "Should indicate center position")
    }

    @Test
    fun `BENDER parses and executes at positive extreme`() = runTest {
        val result = tool.execute(SynthControlTool.Args("BENDER", 1.0f))
        assertTrue(result.success, "BENDER at +1.0 should succeed")
        assertTrue(result.message.contains("up"), "Should indicate up direction")
    }

    @Test
    fun `BENDER parses and executes at negative extreme`() = runTest {
        val result = tool.execute(SynthControlTool.Args("BENDER", -1.0f))
        assertTrue(result.success, "BENDER at -1.0 should succeed")
        assertTrue(result.message.contains("down"), "Should indicate down direction")
    }

    // =========================================================================
    // Case Insensitivity Tests
    // =========================================================================

    @Test
    fun `control IDs are case insensitive`() = runTest {
        val lowercase = tool.execute(SynthControlTool.Args("voice_tune_1", 0.5f))
        assertTrue(lowercase.success, "Lowercase control ID should succeed")

        val uppercase = tool.execute(SynthControlTool.Args("VOICE_TUNE_1", 0.5f))
        assertTrue(uppercase.success, "Uppercase control ID should succeed")

        val mixedCase = tool.execute(SynthControlTool.Args("Voice_Tune_1", 0.5f))
        assertTrue(mixedCase.success, "Mixed case control ID should succeed")
    }

    // =========================================================================
    // Value Clamping Tests
    // =========================================================================

    @Test
    fun `values above 1 are clamped to 1`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VIBRATO", 1.5f))
        assertTrue(result.success, "Value above 1.0 should be clamped and succeed")
        assertTrue(result.message.contains("100%"), "Should be clamped to 100%")
    }

    @Test
    fun `values below 0 are clamped to 0`() = runTest {
        val result = tool.execute(SynthControlTool.Args("VIBRATO", -0.5f))
        assertTrue(result.success, "Value below 0.0 should be clamped and succeed")
        assertTrue(result.message.contains("0%"), "Should be clamped to 0%")
    }
}
