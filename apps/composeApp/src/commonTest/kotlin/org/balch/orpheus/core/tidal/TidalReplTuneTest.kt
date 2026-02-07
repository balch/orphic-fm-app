package org.balch.orpheus.core.tidal

import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.TestSynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.TestDispatcherProvider
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Unit tests for the `tune` command in the TidalRepl.
 * 
 * Voice tuning uses a 0.0-1.0 range where:
 * - 0.0 = A1 (55 Hz base)
 * - 0.5 = A3 (220 Hz base) - the "unity" point
 * - 1.0 = A5 (880 Hz base)
 * 
 * The formula: baseFreq = 55.0 * 2^(tune * 4.0)
 * Each voice also has a pitch multiplier (0.5x, 1.0x, or 2.0x) and quad pitch offset.
 * 
 * Semitone offset from A3 (at tune=0.5): tuneValue = 0.5 + (semitones / 48.0)
 * Common note offsets from A3:
 * - C4 = +3 semitones → tune = 0.5625
 * - E4 = +7 semitones → tune = 0.6458
 * - G4 = +10 semitones → tune = 0.7083
 */
class TidalReplTuneTest {

    private lateinit var repl: TidalRepl

    private val dispatcherProvider = TestDispatcherProvider()

    @BeforeTest
    fun setup() {
        val synthEngine = TestSynthEngine()
        val synthController = SynthController()
        val playbackLifecycleManager = PlaybackLifecycleManager()
        val scheduler = TidalScheduler(
            synthController,
            synthEngine,
            playbackLifecycleManager,
            dispatcherProvider,
        )
        repl = TidalRepl(scheduler, dispatcherProvider)
    }

    // =========================================================================
    // Tune Command - Colon Syntax
    // =========================================================================

    @Test
    fun `evaluate supports tune command with colon syntax`() = runTest {
        // tune:1 0.6 sets voice 1 to tune value 0.6
        val result = repl.evaluateSuspend("tune:1 0.6")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports tune command for all voices`() = runTest {
        // Verify tune works for voices 1-8
        for (voice in 1..8) {
            val result = repl.evaluateSuspend("tune:$voice 0.5")
            assertIs<ReplResult.Success>(result, "tune:$voice should succeed")
        }
    }

    @Test
    fun `evaluate rejects tune command for invalid voice index`() = runTest {
        // Voice 0 should be invalid (1-indexed)
        val result0 = repl.evaluateSuspend("tune:0 0.5")
        assertIs<ReplResult.Error>(result0)

        // Voice 9 should be invalid for user-accessible tune
        val result9 = repl.evaluateSuspend("tune:9 0.5")
        assertIs<ReplResult.Error>(result9)
    }

    // =========================================================================
    // Tune Command - Space Syntax
    // =========================================================================

    @Test
    fun `evaluate supports tune command with space syntax`() = runTest {
        // tune 1 0.6 (space instead of colon)
        val result = repl.evaluateSuspend("tune 1 0.6")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports tune command with implicit voice`() = runTest {
        // tune 0.6 should default to voice 1
        val result = repl.evaluateSuspend("tune 0.6")
        assertIs<ReplResult.Success>(result)
    }

    // =========================================================================
    // Tune Command - Quoted Syntax
    // =========================================================================

    @Test
    fun `evaluate supports tune command with quoted syntax`() = runTest {
        // tune "1 0.6" (quoted form)
        val result = repl.evaluateSuspend("tune \"1 0.6\"")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports tune command with quoted single value`() = runTest {
        // tune "0.6" should default to voice 1
        val result = repl.evaluateSuspend("tune \"0.6\"")
        assertIs<ReplResult.Success>(result)
    }

    // =========================================================================
    // Tune Command - Value Range
    // =========================================================================

    @Test
    fun `evaluate supports tune command at min value`() = runTest {
        val result = repl.evaluateSuspend("tune:1 0.0")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports tune command at max value`() = runTest {
        val result = repl.evaluateSuspend("tune:1 1.0")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports tune command at unity value`() = runTest {
        // 0.5 is the "unity" point (A3/220Hz base)
        val result = repl.evaluateSuspend("tune:1 0.5")
        assertIs<ReplResult.Success>(result)
    }

    // =========================================================================
    // Tune Command - In Pattern Slots
    // =========================================================================

    @Test
    fun `evaluate supports tune in d-slot pattern`() = runTest {
        // d1 $ tune:1 0.6 (cycled pattern)
        val result = repl.evaluateSuspend("d1 \$ tune:1 0.6")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports tune combined with voices via hash`() = runTest {
        // d1 $ voices "1 2" # tune:1 0.5 (combined pattern - tune applies to voice 1)
        val result = repl.evaluateSuspend("d1 \$ voices \"1 2\" # tune:1 0.5")
        assertIs<ReplResult.Success>(result)
    }

    // =========================================================================
    // Tune Command - Musical Note Examples
    // =========================================================================

    @Test
    fun `evaluate supports tune for C4 note`() = runTest {
        // C4 is +3 semitones from A3 → tune = 0.5 + (3/48) = 0.5625
        val result = repl.evaluateSuspend("tune:1 0.5625")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports tune for E4 note`() = runTest {
        // E4 is +7 semitones from A3 → tune = 0.5 + (7/48) ≈ 0.6458
        val result = repl.evaluateSuspend("tune:1 0.6458")
        assertIs<ReplResult.Success>(result)
    }

    @Test
    fun `evaluate supports tune for G4 note`() = runTest {
        // G4 is +10 semitones from A3 → tune = 0.5 + (10/48) ≈ 0.7083
        val result = repl.evaluateSuspend("tune:1 0.7083")
        assertIs<ReplResult.Success>(result)
    }
}
