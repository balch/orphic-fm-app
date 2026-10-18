package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrpheusEngineGrainTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `MST is engine 300 and is appended after OSC`() {
        assertEquals(300, OrpheusEngineId.MST.id)
        assertEquals(OrpheusEngineId.entries.last(), OrpheusEngineId.MST)
        assertEquals(OrpheusEngineId.OSC.ordinal + 1, OrpheusEngineId.MST.ordinal,
            "MST must not shift OSC or any earlier ordinal: saved data stores them")
        assertTrue(OrpheusEngineId.MST.isNativeOnly)
        assertTrue(OrpheusEngineId.MST.isPulsarOnly)
        assertFalse(OrpheusEngineId.VA.isPulsarOnly)
    }

    @Test
    fun `grain fields default to the values the C++ engine seeds`() {
        val e = OrpheusEngine(engineId = OrpheusEngineId.MST)
        assertEquals(GrainMode.BURST, e.grainMode)
        assertEquals(GrainScatterMode.CHORD, e.grainScatterMode)
        assertEquals(GrainVoicing.EVEN, e.grainVoicing)
        assertEquals(0f, e.grainScatter)
        assertEquals(0.5f, e.grainRate)
        assertEquals(false, e.grainRandomTiming)
        assertEquals(0.5f, e.grainShape)
        assertEquals(1f, e.grainBlend)
    }

    @Test
    fun `grain enum ids are their ordinals`() {
        GrainMode.entries.forEach { assertEquals(it.ordinal, it.id) }
        GrainScatterMode.entries.forEach { assertEquals(it.ordinal, it.id) }
        GrainVoicing.entries.forEach { assertEquals(it.ordinal, it.id) }
    }

    @Test
    fun `grain fields survive a serialization round trip`() {
        val e = OrpheusEngine(
            engineId = OrpheusEngineId.MST,
            grainMode = GrainMode.CONTINUOUS,
            grainScatterMode = GrainScatterMode.RAW,
            grainVoicing = GrainVoicing.RISING,
            grainScatter = 0.4f, grainRate = 0.9f, grainRandomTiming = true,
            grainShape = 0.1f, grainBlend = 0.6f,
        )
        assertEquals(e, json.decodeFromString<OrpheusEngine>(json.encodeToString(e)))
    }

    @Test
    fun `an engine saved before the grain fields existed loads with defaults`() {
        val old = """{"engineId":"VA","volume":0.7,"harmonics":0.2}"""
        val e = json.decodeFromString<OrpheusEngine>(old)
        assertEquals(OrpheusEngine(engineId = OrpheusEngineId.VA, volume = 0.7f, harmonics = 0.2f), e)
        assertEquals(GrainMode.BURST, e.grainMode)
        assertEquals(GrainVoicing.EVEN, e.grainVoicing)
        assertEquals(1f, e.grainBlend)
    }

    @Test
    fun `out of range grain values are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            OrpheusEngine(engineId = OrpheusEngineId.MST, grainScatter = 1.5f)
        }
        assertFailsWith<IllegalArgumentException> {
            OrpheusEngine(engineId = OrpheusEngineId.MST, grainRate = -0.1f)
        }
        assertFailsWith<IllegalArgumentException> {
            OrpheusEngine(engineId = OrpheusEngineId.MST, grainShape = 2f)
        }
        assertFailsWith<IllegalArgumentException> {
            OrpheusEngine(engineId = OrpheusEngineId.MST, grainBlend = -1f)
        }
    }

    @Test
    fun `vibrato defaults to off at 5_5 Hz`() {
        val e = OrpheusEngine(engineId = OrpheusEngineId.MST)
        assertEquals(0f, e.vibratoDepth)
        assertEquals(5.5f, e.vibratoRate)
    }

    @Test
    fun `out of range vibrato values are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, vibratoDepth = -1f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, vibratoDepth = 100.5f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, vibratoRate = 0.4f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, vibratoRate = 12.5f) }
        OrpheusEngine(engineId = OrpheusEngineId.VA, vibratoDepth = 100f, vibratoRate = 12f)
        OrpheusEngine(engineId = OrpheusEngineId.VA, vibratoDepth = 0f, vibratoRate = 0.5f)
    }

    @Test
    fun `pitch rise defaults to off with a 2 second time`() {
        val e = OrpheusEngine(engineId = OrpheusEngineId.MST)
        assertEquals(0f, e.pitchRise)
        assertEquals(2f, e.pitchRiseTime)
    }

    @Test
    fun `out of range pitch rise values are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, pitchRise = -1f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, pitchRise = 1200.5f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, pitchRiseTime = 0.05f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, pitchRiseTime = 8.5f) }
        OrpheusEngine(engineId = OrpheusEngineId.VA, pitchRise = 1200f, pitchRiseTime = 8f)
        OrpheusEngine(engineId = OrpheusEngineId.VA, pitchRise = 0f, pitchRiseTime = 0.1f)
    }

    @Test
    fun `harmonics rise defaults to off with a 2 second time`() {
        val e = OrpheusEngine(engineId = OrpheusEngineId.MST)
        assertEquals(0f, e.harmonicsRise)
        assertEquals(2f, e.harmonicsRiseTime)
    }

    @Test
    fun `out of range harmonics rise values are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, harmonicsRise = -1.1f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, harmonicsRise = 1.1f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, harmonicsRiseTime = 0.05f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.VA, harmonicsRiseTime = 8.5f) }
        OrpheusEngine(engineId = OrpheusEngineId.VA, harmonicsRise = 1f, harmonicsRiseTime = 8f)
        OrpheusEngine(engineId = OrpheusEngineId.VA, harmonicsRise = -1f, harmonicsRiseTime = 0.1f)
    }
}
