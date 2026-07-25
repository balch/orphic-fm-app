package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Schema contract for the bass line channel: [Vibe.bassLine] and a
 * [LickSource.BASS] track must arrive together, both directions.
 */
class BassLineTest {

    // Known-good 8-track vibe with no lick/rotation of its own.
    private val base = DogHouseVibe().vibe
    private val smallLick = Lick(listOf(LickStep(0, 0.5f)), loopLength = 8)

    /** Copy of the base tracks with one track flipped to a BASS-source Fill lick. */
    private fun bassTracks(trackIndex: Int = 3): List<TrackVoice> {
        val tracks = base.tracks.toMutableList()
        tracks[trackIndex] = tracks[trackIndex].copy(
            role = TrackRole.Melodic(lickMode = LickMode.Fill, lickSource = LickSource.BASS)
        )
        return tracks
    }

    @Test
    fun accepts_bass_line_with_a_bass_source_track() {
        val v = base.copy(tracks = bassTracks(), bassLine = smallLick)
        assertEquals(smallLick, v.bassLine)
    }

    @Test
    fun rejects_bass_line_without_a_bass_source_track() {
        assertFailsWith<IllegalArgumentException> { base.copy(bassLine = smallLick) }
    }

    @Test
    fun rejects_bass_source_track_without_bass_line() {
        assertFailsWith<IllegalArgumentException> { base.copy(tracks = bassTracks()) }
    }

    @Test
    fun bass_source_with_lick_mode_none_is_inert_and_allowed() {
        val tracks = base.tracks.toMutableList()
        tracks[3] = tracks[3].copy(role = TrackRole.Melodic(lickSource = LickSource.BASS))
        base.copy(tracks = tracks) // must not throw: no lickMode means no data needed
    }

    @Test
    fun bass_fields_survive_serialization_round_trip() {
        val json = Json { encodeDefaults = true }
        val v = base.copy(
            tracks = bassTracks(),
            bassLine = smallLick,
            bassLineMutation = 0.25f,
            bassLineOctave = 2,
        )
        val decoded = json.decodeFromString<Vibe>(json.encodeToString(Vibe.serializer(), v))
        assertEquals(smallLick, decoded.bassLine)
        assertEquals(0.25f, decoded.bassLineMutation)
        assertEquals(2, decoded.bassLineOctave)
    }
}
