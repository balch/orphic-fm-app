package org.balch.orpheus.features.visualizations.viz.face

import org.balch.orpheus.core.media.PlaybackProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FaceMorphFrameTest {
    @Test fun `stageBlend at morph zero is the first pair with no blend`() {
        assertEquals(StageBlend(0, 0f), stageBlend(0f, 7))
    }

    @Test fun `stageBlend at morph one lands on the last pair fully blended`() {
        assertEquals(StageBlend(5, 1f), stageBlend(1f, 7))
    }

    @Test fun `stageBlend at 0point5 with 7 frames`() {
        assertEquals(StageBlend(3, 0f), stageBlend(0.5f, 7))
    }

    @Test fun `stageBlend just below one stays on the last pair`() {
        val blend = stageBlend(0.999999f, 7)
        assertEquals(5, blend.stage)
        assertTrue(blend.t < 1f && blend.t > 0.99f, "t = ${blend.t}")
    }

    @Test fun `stageBlend with exactly two frames`() {
        assertEquals(StageBlend(0, 0f), stageBlend(0f, 2))
        assertEquals(StageBlend(0, 1f), stageBlend(1f, 2))
        assertEquals(StageBlend(0, 0.5f), stageBlend(0.5f, 2))
    }

    @Test fun `stageBlend treats NaN and out-of-range morph as clamped`() {
        assertEquals(StageBlend(0, 0f), stageBlend(Float.NaN, 7))
        assertEquals(StageBlend(0, 0f), stageBlend(-1f, 7))
        assertEquals(StageBlend(5, 1f), stageBlend(2f, 7))
    }

    @Test fun `stageBlend cap holds the last pair short and leaves earlier stages alone`() {
        assertEquals(StageBlend(5, 0.75f), stageBlend(1f, 7, lastStageCap = 0.75f))
        assertEquals(StageBlend(3, 0f), stageBlend(0.5f, 7, lastStageCap = 0.75f))
        assertEquals(StageBlend(0, 0.25f), stageBlend(1f, 2, lastStageCap = 0.25f))
    }

    @Test fun `stageBlend treats a non-finite cap as uncapped`() {
        assertEquals(StageBlend(5, 1f), stageBlend(1f, 7, lastStageCap = Float.NaN))
    }

    @Test fun `stageBlend requires at least two frames`() {
        assertFailsWith<IllegalArgumentException> { stageBlend(0.5f, 1) }
    }

    @Test fun `songFraction is null without progress`() {
        assertEquals(null, songFraction(null))
    }

    @Test fun `songFraction is null when duration is zero`() {
        assertEquals(null, songFraction(PlaybackProgress(positionMs = 10, durationMs = 0)))
    }

    @Test fun `songFraction clamps a negative position to zero`() {
        assertEquals(0f, songFraction(PlaybackProgress(positionMs = -10, durationMs = 1000)))
    }

    @Test fun `songFraction clamps a position past the duration to one`() {
        assertEquals(1f, songFraction(PlaybackProgress(positionMs = 2000, durationMs = 1000)))
    }

    @Test fun `fallbackFraction at zero`() {
        assertEquals(0f, fallbackFraction(0f))
    }

    @Test fun `fallbackFraction just under a full turn`() {
        assertEquals(239.9f / 240f, fallbackFraction(239.9f), 1e-4f)
    }

    @Test fun `fallbackFraction wraps at a full turn`() {
        assertEquals(0f, fallbackFraction(240f), 1e-4f)
    }

    @Test fun `fallbackFraction wraps repeatedly past several turns`() {
        assertEquals(0.5f, fallbackFraction(600f), 1e-4f)
    }

    @Test fun `signalScaled at knob zero is silent`() {
        assertEquals(0f, signalScaled(1f, 0f))
    }

    @Test fun `signalScaled at knob 0point5 passes the value through`() {
        assertEquals(0.5f, signalScaled(0.5f, 0.5f))
    }

    @Test fun `signalScaled at knob one doubles the value`() {
        assertEquals(1f, signalScaled(0.5f, 1f))
    }

    @Test fun `signalScaled clamps a value past one`() {
        assertEquals(1f, signalScaled(2f, 1f))
    }

    @Test fun `the loudest of the three drum tracks is the drum level, kick included`() {
        assertEquals(0.7f, drumLevel(floatArrayOf(0.7f, 0.2f, 0.1f, 0.9f)), 1e-6f)   // track 3 is bass
        assertEquals(0.4f, drumLevel(floatArrayOf(0.1f, 0.4f, 0.3f)), 1e-6f)
    }

    @Test fun `a short, hostile or over-range level array is safe`() {
        assertEquals(0f, drumLevel(floatArrayOf()), 1e-6f)
        assertEquals(0.5f, drumLevel(floatArrayOf(0.5f)), 1e-6f)
        assertEquals(0.3f, drumLevel(floatArrayOf(Float.NaN, 0.3f, Float.NEGATIVE_INFINITY)), 1e-6f)
        assertEquals(1f, drumLevel(floatArrayOf(4f, 0f, 0f)), 1e-6f)
    }
}
