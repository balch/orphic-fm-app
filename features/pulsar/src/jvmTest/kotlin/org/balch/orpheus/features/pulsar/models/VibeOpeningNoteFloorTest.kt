package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun minimalVibe(openingNoteFloor: Int? = null): Vibe = Vibe(
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
    openingNoteFloor = openingNoteFloor,
)

class VibeOpeningNoteFloorTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `openingNoteFloor defaults to null so existing vibes are unaffected`() {
        assertNull(minimalVibe().openingNoteFloor)
    }

    @Test
    fun `openingNoteFloor survives a serialization round trip`() {
        val v = minimalVibe(openingNoteFloor = 33)
        val encoded = json.encodeToString(v)
        assertEquals(v, json.decodeFromString<Vibe>(encoded))
    }

    @Test
    fun `out of range openingNoteFloor is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { minimalVibe(openingNoteFloor = -1) }
        assertFailsWith<IllegalArgumentException> { minimalVibe(openingNoteFloor = 128) }
    }
}
