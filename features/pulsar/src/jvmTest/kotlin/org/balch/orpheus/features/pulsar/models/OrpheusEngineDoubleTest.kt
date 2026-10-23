package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrpheusEngineDoubleTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `double fields default to off`() {
        val e = OrpheusEngine(engineId = OrpheusEngineId.SPK)
        assertEquals(0f, e.doubleDetune)
        assertEquals(0f, e.doubleSpread)
    }

    @Test
    fun `double detune outside 0 to 50 cents is rejected`() {
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.SPK, doubleDetune = 50.1f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.SPK, doubleDetune = -1f) }
        OrpheusEngine(engineId = OrpheusEngineId.SPK, doubleDetune = 50f)
    }

    @Test
    fun `double spread outside 0 to 1 is rejected`() {
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.SPK, doubleSpread = 1.1f) }
        assertFailsWith<IllegalArgumentException> { OrpheusEngine(engineId = OrpheusEngineId.SPK, doubleSpread = -0.1f) }
    }

    @Test
    fun `vibe json decodes the double fields`() {
        val e = json.decodeFromString<OrpheusEngine>(
            """{"engineId":"SPK","doubleDetune":12,"doubleSpread":0.5}""",
        )
        assertEquals(12f, e.doubleDetune)
        assertEquals(0.5f, e.doubleSpread)
    }
}
