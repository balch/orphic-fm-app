package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PinnedHitsSchemaTest {

    // Tracks 0-2 are Percussive, 3-7 Melodic.
    private fun pinnedVibe(track: Int, hits: List<Int>, stepCount: Int = 32) = Vibe(
        name = "Pinned Hits Schema Test",
        bpm = 100f,
        rootNote = RootNote.C,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(
            swingAmount = 0f, ghostProbability = 0f,
            noteRangeLow = 36, noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.SPARSE.density,
        ),
        tracks = List(8) {
            TrackVoice(
                engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
                engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
                role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
            )
        },
        stepCount = stepCount,
        arrangement = Arrangement(
            sections = listOf(
                Section(name = "pinned", trackOverrides = mapOf(track to TrackSectionOverride(hits = hits))),
            ),
        ),
    )

    @Test
    fun trackSectionOverrideRejectsEmptyDuplicateAndOutOfRangeHits() {
        assertFailsWith<IllegalArgumentException> { TrackSectionOverride(hits = emptyList()) }
        assertFailsWith<IllegalArgumentException> { TrackSectionOverride(hits = listOf(4, 4)) }
        assertFailsWith<IllegalArgumentException> { TrackSectionOverride(hits = listOf(-1)) }
        assertFailsWith<IllegalArgumentException> {
            TrackSectionOverride(hits = listOf(TrackSectionOverride.MAX_HIT_STEPS))
        }
        assertEquals(listOf(0, 63), TrackSectionOverride(hits = listOf(0, 63)).hits)
    }

    @Test
    fun vibeRejectsHitsAtOrPastStepCount() {
        assertFailsWith<IllegalArgumentException> { pinnedVibe(track = 1, hits = listOf(0, 16), stepCount = 16) }
        pinnedVibe(track = 1, hits = listOf(0, 15), stepCount = 16)
    }

    @Test
    fun vibeRejectsHitsOnANonPercussiveTrack() {
        assertFailsWith<IllegalArgumentException> { pinnedVibe(track = 4, hits = listOf(0)) }
        assertFailsWith<IllegalArgumentException> { pinnedVibe(track = 9, hits = listOf(0)) }
        pinnedVibe(track = 1, hits = listOf(0))
    }
}
