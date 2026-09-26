package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.plugin.viz.PulsarVizData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicPulseTest {
    private fun viz(step: Int, vararg levels: Float, steps: Int = 16) = PulsarVizData(
        playheads = IntArray(8) { step },
        stepCounts = IntArray(8) { steps },
        trackLevels = FloatArray(8) { levels.getOrElse(it) { 0f } },
    )

    private fun levels(vararg levels: Float) = FloatArray(8) { levels.getOrElse(it) { 0f } }

    @Test
    fun oneStoryBeatPerBeatBoundary() {
        val acc = BeatAccumulator()
        val beats = (0..8).mapNotNull { acc.add(viz(it, 0.5f), it * 125L) }
        assertEquals(2, beats.size, "steps 0..8 cross the beats at 4 and 8")
    }

    @Test
    fun aPlayheadWrapIsABoundary() {
        val acc = BeatAccumulator()
        (12..15).forEach { assertNull(acc.add(viz(it, 0.3f), it * 125L)) }
        assertNotNull(acc.add(viz(0, 0.3f), 16 * 125L))
    }

    @Test
    fun heldPlayheadsRecordNothing() {
        val acc = BeatAccumulator()
        acc.add(viz(2, 0.8f), 0L)
        repeat(200) { assertNull(acc.add(viz(2, (it % 10) / 10f), it * 16L)) }
    }

    @Test
    fun aBeatsLevelIsItsLoudestMoment() {
        val acc = BeatAccumulator()
        acc.add(viz(0, 0.1f), 0L)
        acc.add(viz(1, 0.9f, 0.5f), 125L)
        acc.add(viz(2, 0.2f), 250L)
        acc.add(viz(3), 375L)
        val beat = assertNotNull(acc.add(viz(4), 500L))
        assertEquals(musicLoudness(levels(0.9f, 0.5f)), beat.level, 1e-6f)
    }

    @Test
    fun trackEnergyAccumulatesAcrossTheBeat() {
        val acc = BeatAccumulator()
        acc.add(viz(0, 0.1f, 0f, 0.2f), 0L)
        acc.add(viz(1, 0.3f, 0f, 0.2f), 125L)
        acc.add(viz(2, 0f, 0f, 0.2f), 250L)
        acc.add(viz(3, 0f, 0f, 0.2f), 375L)
        val beat = assertNotNull(acc.add(viz(4, 1f, 1f, 1f), 500L))
        assertEquals(0.4f, beat.trackEnergy[0], 1e-6f)
        assertEquals(0f, beat.trackEnergy[1], 1e-6f)
        assertEquals(0.8f, beat.trackEnergy[2], 1e-6f)
    }

    // The downbeat's own sample (the kick's transient) belongs to the beat it opens.
    @Test
    fun theDownbeatSampleOpensTheNextBeat() {
        val acc = BeatAccumulator()
        (0..3).forEach { acc.add(viz(it, 0.1f), it * 125L) }
        acc.add(viz(4, 0.9f), 500L)
        (5..7).forEach { acc.add(viz(it, 0.1f), it * 125L) }
        val second = assertNotNull(acc.add(viz(8), 1_000L))
        assertEquals(musicLoudness(levels(0.9f)), second.level, 1e-6f)
    }

    @Test
    fun aResetDropsThePendingBeat() {
        val acc = BeatAccumulator()
        acc.add(viz(0, 1f), 0L)
        acc.add(viz(1, 1f), 125L)
        acc.reset()
        acc.add(viz(2, 0.1f), 250L)
        acc.add(viz(3, 0.1f), 375L)
        val beat = assertNotNull(acc.add(viz(4), 500L))
        assertEquals(musicLoudness(levels(0.1f)), beat.level, 1e-6f)
    }

    @Test
    fun loudnessRisesWithTheMixAndStaysUnderOne() {
        assertEquals(0f, musicLoudness(FloatArray(8)))
        val pad = musicLoudness(levels(0f, 0f, 0f, 0f, 0f, 0.15f))
        val band = musicLoudness(levels(0.8f, 0.4f, 0.3f, 0.6f, 0.3f, 0.3f))
        assertTrue(pad in 0.05f..0.3f, "a lone quiet pad should read low: $pad")
        assertTrue(band > 0.8f && band < 1f, "a full mix should read near 1: $band")
    }

    @Test
    fun theReferenceTrackHasTheMostSteps() {
        assertEquals(2, beatReferenceTrack(IntArray(8) { 0 }, intArrayOf(16, 12, 24, 16, 16, 16, 16, 16)))
    }

    @Test
    fun aTieFallsToTheLowestTrack() {
        assertEquals(0, beatReferenceTrack(IntArray(8) { 3 }, IntArray(8) { 16 }))
    }

    @Test
    fun aStoppedTrackIsNeverTheReference() {
        val playheads = IntArray(8) { 5 }.also { it[2] = -1 }
        assertEquals(0, beatReferenceTrack(playheads, intArrayOf(16, 16, 24, 16, 16, 16, 16, 16)))
        assertEquals(-1, beatReferenceTrack(IntArray(8) { -1 }, IntArray(8) { 16 }))
    }

    // The viz window clamps at 32 steps, so a 64-step track's playhead parks at 31 for half its loop.
    @Test
    fun aTrackAtTheVizWindowYieldsToOneBelowIt() {
        assertEquals(1, beatReferenceTrack(IntArray(8) { 0 }, intArrayOf(32, 16, 32, 16, 16, 16, 16, 16)))
        assertEquals(0, beatReferenceTrack(IntArray(8) { 0 }, IntArray(8) { 32 }))
    }

    @Test
    fun beatPhaseIsZeroOnTheBeat() {
        assertEquals(0f, beatPhase(step = 0, msSinceStep = 0f, msPerStep = 125f))
        assertEquals(0f, beatPhase(step = 4, msSinceStep = 0f, msPerStep = 125f))
    }

    @Test
    fun beatPhaseClimbsBetweenSteps() {
        val phases = (0..3).flatMap { step -> (0 until 125 step 25).map { beatPhase(step, it.toFloat(), 125f) } }
        phases.zipWithNext().forEach { (a, b) -> assertTrue(b > a, "beat phase fell back: $a -> $b") }
        assertEquals(0.125f, beatPhase(step = 0, msSinceStep = 62.5f, msPerStep = 125f), 1e-6f)
    }

    @Test
    fun beatPhaseWrapsAtTheNextBeat() {
        assertTrue(beatPhase(step = 3, msSinceStep = 124f, msPerStep = 125f) > 0.99f)
        assertEquals(0f, beatPhase(step = 8, msSinceStep = 0f, msPerStep = 125f))
    }

    @Test
    fun anOverdueStepHoldsAtItsEnd() {
        assertEquals(0.5f, beatPhase(step = 1, msSinceStep = 900f, msPerStep = 125f), 1e-6f)
        assertEquals(0.5f, beatPhase(step = 2, msSinceStep = 900f, msPerStep = 0f), 1e-6f)
    }

    @Test
    fun thePulseMeasuresTheTempoFromTheSteps() {
        val acc = BeatAccumulator()
        (0..9).forEach { acc.add(viz(it), it * 125L) }
        assertEquals(500f, acc.pulse.msPerBeat, 1f)
    }

    @Test
    fun thePulseMovesSmoothlyBetweenSteps() {
        val acc = BeatAccumulator()
        (0..9).forEach { acc.add(viz(it), it * 125L) }
        acc.add(viz(9, 0.2f), 9 * 125L + 62L)
        assertEquals((1 + 62f / 125f) / 4f, acc.pulse.beatPhase, 1e-3f)
        assertEquals(musicLoudness(levels(0.2f)), acc.pulse.level, 1e-6f)
        assertEquals(0.2f, acc.pulse.trackLevels[0])
    }

    // A loop of 4 steps or fewer never changes step / 4; its wrap is the beat.
    @Test
    fun aShortLoopWrapClosesABeat() {
        val acc = BeatAccumulator()
        val beats = listOf(0, 1, 2, 0, 1, 2, 0).mapIndexedNotNull { i, step -> acc.add(viz(step, 0.4f, steps = 3), i * 125L) }
        assertEquals(2, beats.size)
    }

    @Test
    fun beatsCarryTheirSongPosition() {
        val acc = BeatAccumulator()
        acc.onProgress(positionMs = 10_000L, nowMs = 0L)
        val beats = (0..8).mapNotNull { acc.add(viz(it, 0.5f), it * 125L) }
        assertEquals(listOf(10_000L, 10_500L), beats.map { it.positionMs })
        assertEquals(listOf(500L, 500L), beats.map { it.durationMs })
    }

    @Test
    fun withoutAPositionABeatHasNone() {
        val acc = BeatAccumulator()
        val beat = assertNotNull((0..4).mapNotNull { acc.add(viz(it, 0.5f), it * 125L) }.firstOrNull())
        assertEquals(-1L, beat.positionMs)
    }

    // A pause holds the playheads, so the song clock (counted in steps) holds too.
    @Test
    fun aPauseDoesNotShiftPositions() {
        val acc = BeatAccumulator()
        acc.onProgress(positionMs = 0L, nowMs = 0L)
        (0..3).forEach { acc.add(viz(it, 0.5f), it * 125L) }
        // Paused 30 s on step 3; step 4 plays on resume.
        val beforePause = assertNotNull(acc.add(viz(4, 0.5f), 30_000L))
        assertEquals(0L, beforePause.positionMs)
        assertEquals(500L, beforePause.durationMs)
        (5..7).forEach { acc.add(viz(it, 0.5f), 30_000L + (it - 4) * 125L) }
        val afterPause = assertNotNull(acc.add(viz(8, 0.5f), 30_500L))
        assertEquals(500L, afterPause.positionMs, "the beat from step 4 starts 4 steps into the song")
    }

    // Hidden, the viz stops while the song runs on. The beat pending at the gap closes where it
    // stopped, never stretched across the gap, and none of its samples leak into the next beat.
    @Test
    fun aGapClosesThePendingBeatWhereItStopped() {
        val acc = BeatAccumulator()
        acc.onProgress(positionMs = 0L, nowMs = 0L)
        (0..3).forEach { acc.add(viz(it, 0.1f), it * 125L) }
        assertNotNull(acc.add(viz(4, 0.9f), 500L))
        acc.add(viz(5, 0.9f), 625L)
        val pending = assertNotNull(acc.add(viz(9, 0.1f), 20_000L), "the gap should close the pending beat")
        assertEquals(500L, pending.positionMs)
        assertEquals(250L, pending.durationMs, "the pending beat stretched across the gap")
        assertEquals(musicLoudness(levels(0.9f)), pending.level, 1e-6f)
        (10..11).forEach { assertNull(acc.add(viz(it, 0.1f), 20_000L + (it - 9) * 125L)) }
        val next = assertNotNull(acc.add(viz(12, 0.1f), 20_375L))
        assertEquals(musicLoudness(levels(0.1f)), next.level, 1e-6f, "pre-gap samples leaked into the next beat")
    }

    // Progress kept moving with no steps seen: the song ran unwatched, so the clock, not the steps, places beats.
    @Test
    fun beatsAfterAnUnwatchedStretchLandAtTheirTime() {
        val acc = BeatAccumulator()
        acc.onProgress(positionMs = 0L, nowMs = 0L)
        (0..7).forEach { acc.add(viz(it, 0.5f), it * 125L) }
        acc.onProgress(positionMs = 2_000L, nowMs = 2_000L)
        acc.onProgress(positionMs = 4_000L, nowMs = 4_000L)
        acc.onProgress(positionMs = 6_000L, nowMs = 6_000L)
        acc.add(viz(4, 0.5f), 6_500L)
        (5..7).forEach { acc.add(viz(it, 0.5f), 6_500L + (it - 4) * 125L) }
        val beat = assertNotNull(acc.add(viz(8, 0.5f), 7_000L))
        assertEquals(6_500L, beat.positionMs)
    }

    // A pause is one long gap between steps; it must not stretch the measured tempo.
    @Test
    fun aPauseDoesNotStretchTheTempo() {
        val acc = BeatAccumulator()
        (0..5).forEach { acc.add(viz(it), it * 125L) }
        (6..9).forEach { acc.add(viz(it), 10_000L + it * 125L) }
        assertEquals(500f, acc.pulse.msPerBeat, 1f)
    }
}
