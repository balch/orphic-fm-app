package org.balch.orpheus.features.pulsar.models

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun minimalArrangement(
    minVibeSeconds: Int = 150,
    maxVibeSeconds: Int = 300,
    transitionOut: TransitionSpec? = null,
): Arrangement = Arrangement(
    sections = listOf(Section(name = "loop")),
    minVibeSeconds = minVibeSeconds,
    maxVibeSeconds = maxVibeSeconds,
    transitionOut = transitionOut,
)

class ArrangementEndingFieldsTest {
    @Test
    fun `defaults are 150 to 300 seconds and null transitionOut`() {
        val a = minimalArrangement()
        assertEquals(150, a.minVibeSeconds)
        assertEquals(300, a.maxVibeSeconds)
        assertNull(a.transitionOut, "transitionOut defaults to null (inherit global default)")
    }

    @Test
    fun `TransitionSpec defaults to null handoffMs and empty randomPool`() {
        // The runner picks the style-specific default duration when handoffMs
        // is null. randomPool is consulted only for RANDOM.
        val spec = TransitionSpec(TransitionStyle.FADE)
        assertNull(spec.handoffMs, "handoffMs defaults to null (style-specific default)")
        assertEquals(emptyList(), spec.randomPool)
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
