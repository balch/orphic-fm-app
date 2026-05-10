package org.balch.orpheus.features.pulsar.models

import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun minimalArrangement(
    minVibeSeconds: Int = 150,
    maxVibeSeconds: Int = 300,
    endStyle: EndStyle = EndStyle.ABRUPT,
): Arrangement = Arrangement(
    sections = listOf(Section(name = "loop")),
    minVibeSeconds = minVibeSeconds,
    maxVibeSeconds = maxVibeSeconds,
    endStyle = endStyle,
)

class ArrangementEndingFieldsTest {
    @Test
    fun `defaults are 150 to 300 seconds and ABRUPT`() {
        val a = minimalArrangement()
        assertEquals(150, a.minVibeSeconds)
        assertEquals(300, a.maxVibeSeconds)
        assertEquals(EndStyle.ABRUPT, a.endStyle)
    }

    @Test
    fun `EndStyle ramp bars`() {
        assertEquals(0f, EndStyle.ABRUPT.rampBars)
        assertEquals(0.25f, EndStyle.FADE_FAST.rampBars)
        assertEquals(1f, EndStyle.FADE_SLOW.rampBars)
    }

    @Test
    fun `minVibeSeconds below 30 is rejected`() {
        assertFailsWith<IllegalArgumentException> { minimalArrangement(minVibeSeconds = 10) }
    }

    @Test
    fun `maxVibeSeconds below min is rejected`() {
        assertFailsWith<IllegalArgumentException> { minimalArrangement(minVibeSeconds = 200, maxVibeSeconds = 100) }
    }

    @Test
    fun `maxVibeSeconds above 1800 is rejected`() {
        assertFailsWith<IllegalArgumentException> { minimalArrangement(maxVibeSeconds = 2000) }
    }

    @Test
    fun `Vibe with no arrangement opts out of song-ending entirely`() {
        // Vibe still constructs without any ending knobs.
        val v = Vibe(
            name = "Test",
            tracks = List(8) {
                TrackVoice(
                    engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
                    engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
                )
            },
            bpm = 120f,
            rootNote = RootNote.A,
            scaleType = ScaleType.MINOR,
            genre = GenreProfile(
                swingAmount = 0f, ghostProbability = 0f,
                noteRangeLow = 36, noteRangeHigh = 72,
                rhythmDensity = RhythmPattern.SPARSE.density,
            ),
        )
        assertEquals(null, v.arrangement)
    }
}
