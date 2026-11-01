package org.balch.orpheus.features.visualizations.viz.ants

import org.balch.orpheus.core.media.PlaybackProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongEndClockTest {
    @Test fun `no progress or an endless song has no end coming`() {
        val clock = SongEndClock()
        assertNull(clock.secondsLeft(null, 0.016f))
        assertNull(clock.secondsLeft(PlaybackProgress(90_000, 120_000, durationFinal = false, endless = true), 0.016f))
    }

    @Test fun `an end not yet locked in is coming but unknown`() {
        val clock = SongEndClock()
        assertEquals(Float.MAX_VALUE, clock.secondsLeft(PlaybackProgress(10_000, 180_000, durationFinal = false), 0.016f))
    }

    @Test fun `counts down between bar updates and resyncs on the next`() {
        val clock = SongEndClock()
        assertEquals(10f, clock.secondsLeft(PlaybackProgress(110_000, 120_000), 0.016f)!!, 1e-3f)
        // Half a second of frames with no new bar.
        var left = 0f
        repeat(25) { left = clock.secondsLeft(PlaybackProgress(110_000, 120_000), 0.02f)!! }
        assertEquals(9.5f, left, 1e-3f)
        // The next bar lands two seconds on: back to the published position.
        assertEquals(8f, clock.secondsLeft(PlaybackProgress(112_000, 120_000), 0.02f)!!, 1e-3f)
    }
}
