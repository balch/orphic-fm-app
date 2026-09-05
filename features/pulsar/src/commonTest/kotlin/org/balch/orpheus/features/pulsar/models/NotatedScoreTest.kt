package org.balch.orpheus.features.pulsar.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

class NotatedScoreTest {

    private fun ev(tick: Int, pitch: Int = 60) =
        ScoreEvent(tick = tick, durationTicks = 24, pitch = pitch, velocity = 96)

    @Test
    fun `a well-formed part is accepted`() {
        val part = NotatedPart(trackIndex = 4, name = "Lead", events = listOf(ev(0), ev(24), ev(48)))
        assertEquals(3, part.events.size)
    }

    @Test
    fun `descending ticks are rejected`() {
        // The C++ scheduler walks a cursor forward and never sorts, so an
        // out-of-order list would silently skip notes instead of failing.
        assertFailsWith<IllegalArgumentException> {
            NotatedPart(trackIndex = 4, name = "Lead", events = listOf(ev(48), ev(24)))
        }
    }

    @Test
    fun `notes sharing a tick are accepted as a chord`() {
        // Was rejected: two notes at one tick used to be unrenderable polyphony. The voice
        // pool allocates a slot each now, so this is the feature rather than a violation.
        // Descending ticks stay rejected — see the ordering test above.
        val part = NotatedPart(
            trackIndex = 4, name = "Lead",
            events = listOf(ev(24), ev(24, pitch = 64), ev(24, pitch = 67)),
        )
        assertEquals(3, part.events.size)
        assertEquals(1, part.events.map { it.tick }.distinct().size)
    }

    @Test
    fun `an out-of-range pitch is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotatedPart(trackIndex = 4, name = "L", events = listOf(ev(0).copy(pitch = 128)))
        }
    }

    @Test
    fun `an out-of-range velocity is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotatedPart(trackIndex = 4, name = "L", events = listOf(ev(0).copy(velocity = 128)))
        }
    }

    @Test
    fun `a zero duration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotatedPart(trackIndex = 4, name = "L", events = listOf(ev(0).copy(durationTicks = 0)))
        }
    }

    @Test
    fun `a negative tick is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotatedPart(trackIndex = 4, name = "L", events = listOf(ev(0).copy(tick = -1)))
        }
    }

    @Test
    fun `a track index outside 0 to 7 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotatedPart(trackIndex = 8, name = "L", events = listOf(ev(0)))
        }
    }

    @Test
    fun `more events than the engine can hold is rejected at construction`() {
        // The routing layer silently drops writes past its bound, so an oversized
        // score must fail here rather than arriving with its tail missing.
        val tooMany = (0..NotatedScore.MAX_SCORE_EVENTS).map { ev(it * 2) }
        assertFailsWith<IllegalArgumentException> {
            NotatedPart(trackIndex = 4, name = "L", events = tooMany)
        }
    }

    @Test
    fun `an empty part is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotatedPart(trackIndex = 4, name = "L", events = emptyList())
        }
    }

    @Test
    fun `two parts on the same track are rejected`() {
        val a = NotatedPart(trackIndex = 4, name = "A", events = listOf(ev(0)))
        val b = NotatedPart(trackIndex = 4, name = "B", events = listOf(ev(0)))
        assertFailsWith<IllegalArgumentException> {
            NotatedScore(name = "X", parts = listOf(a, b))
        }
    }

    @Test
    fun `a score with no parts is rejected`() {
        assertFailsWith<IllegalArgumentException> { NotatedScore(name = "X", parts = emptyList()) }
    }

    @Test
    fun `a ppq other than 96 is rejected`() {
        // Every position in the model is an integer tick at 96 PPQ. A different
        // resolution would silently misplace every event.
        val part = NotatedPart(trackIndex = 4, name = "L", events = listOf(ev(0)))
        assertFailsWith<IllegalArgumentException> {
            NotatedScore(name = "X", ppq = 480, parts = listOf(part))
        }
    }

    @Test
    fun partTimbreDefaultsMatchEngineDefaults() {
        val t = PartTimbre()
        assertEquals(0, t.engineIndex)
        assertEquals(0.5f, t.harmonics); assertEquals(0.5f, t.timbre)
        assertEquals(0.5f, t.morph); assertEquals(0.5f, t.decay)
        assertEquals(1f, t.level); assertEquals(0f, t.colorResponse)
    }

    @Test
    fun partTimbreRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> { PartTimbre(harmonics = 1.2f) }
        assertFailsWith<IllegalArgumentException> { PartTimbre(engineIndex = -1) }
        assertFailsWith<IllegalArgumentException> { PartTimbre(level = -0.1f) }
    }

    @Test
    fun scoreEventRoundTripsBandRelease() {
        val e = ScoreEvent(tick = 0, durationTicks = 96, pitch = 60, velocity = 100, hold = true, bandRelease = true)
        val json = Json.encodeToString(ScoreEvent.serializer(), e)
        assertEquals(e, Json.decodeFromString(ScoreEvent.serializer(), json))
    }
}
