package org.balch.orpheus.features.pulsar

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.Rest
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import kotlin.test.Test
import kotlinx.serialization.SerializationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VibeSerializationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `DogHouse vibe round-trips through JSON unchanged`() {
        val original = DogHouseVibe().vibe
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Vibe>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `legacy negative degree without a rest key decodes as a rest`() {
        val step = json.decodeFromString<LickStep>("""{"scaleDegree":-1,"duration":0.5}""")
        assertEquals(true, step.rest)
        assertEquals(0.5f, step.duration)
        assertEquals(Rest(0.5f).velocity, step.velocity)
        val breath = json.decodeFromString<LickStep>("""{"scaleDegree":-1,"duration":0.25,"velocity":0.45}""")
        assertEquals(0.45f, breath.velocity)
    }

    @Test
    fun `a rest with no velocity key decodes at the default velocity`() {
        val step = json.decodeFromString<LickStep>("""{"scaleDegree":0,"duration":1,"rest":true}""")
        assertEquals(Rest(1f), step)
    }

    @Test
    fun `a rest with no scaleDegree decodes as degree 0`() {
        val step = json.decodeFromString<LickStep>("""{"rest":true,"duration":0.5}""")
        assertEquals(Rest(0.5f), step)
    }

    @Test
    fun `a note with no scaleDegree fails naming the field`() {
        for (body in listOf("""{"duration":0.5}""", """{"duration":0.5,"rest":false}""")) {
            val e = assertFailsWith<SerializationException> { json.decodeFromString<LickStep>(body) }
            assertTrue(e.message.orEmpty().contains("scaleDegree"), e.message)
        }
    }

    @Test
    fun `explicit rest false keeps a below-root note`() {
        val step = json.decodeFromString<LickStep>("""{"scaleDegree":-1,"duration":0.5,"rest":false}""")
        assertEquals(false, step.rest)
        assertEquals(-1, step.scaleDegree)
    }

    @Test
    fun `rest is written even when defaults are omitted`() {
        val lean = Json { encodeDefaults = false }
        val note = LickStep(-2, 1f)
        val noteJson = lean.encodeToString(note)
        assertTrue(noteJson.contains(""""rest":false"""), noteJson)
        assertEquals(note, lean.decodeFromString<LickStep>(noteJson))
        val restJson = lean.encodeToString(Rest(0.5f))
        assertTrue(restJson.contains(""""rest":true"""), restJson)
        assertEquals(Rest(0.5f), lean.decodeFromString<LickStep>(restJson))
    }
}
