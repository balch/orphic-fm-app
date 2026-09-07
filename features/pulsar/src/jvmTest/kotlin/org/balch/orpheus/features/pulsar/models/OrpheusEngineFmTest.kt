package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrpheusEngineFmTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `fm fields default to zero so existing vibes are byte-identical`() {
        val e = OrpheusEngine(engineId = OrpheusEngineId.OSC)
        assertEquals(0f, e.fmRatio)
        assertEquals(0f, e.fmShape)
        assertEquals(0f, e.fmFreeHz)
    }

    @Test
    fun `fm fields survive a serialization round trip`() {
        val e = OrpheusEngine(
            engineId = OrpheusEngineId.OSC,
            fmRatio = 2.5f, fmShape = 0.3f, fmFreeHz = 180f,
        )
        val encoded = json.encodeToString(e)
        assertEquals(e, json.decodeFromString<OrpheusEngine>(encoded))
    }

    @Test
    fun `out of range fm values are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            OrpheusEngine(engineId = OrpheusEngineId.OSC, fmRatio = 17f)
        }
        assertFailsWith<IllegalArgumentException> {
            OrpheusEngine(engineId = OrpheusEngineId.OSC, fmShape = 1.5f)
        }
        assertFailsWith<IllegalArgumentException> {
            OrpheusEngine(engineId = OrpheusEngineId.OSC, fmFreeHz = -1f)
        }
    }
}
