package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoidAnomalyTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun roundTripsThroughJson() {
        val v = VoidAnomaly(
            probability = 0.2f, floorLevel = 0.1f, rampDownBars = 2.5f,
            floorBarsMin = 0.5f, floorBarsMax = 3.0f, rampUpBars = 0.75f, ghostIntensity = 0.8f,
        )
        val decoded = json.decodeFromString(
            VoidAnomaly.serializer(),
            json.encodeToString(VoidAnomaly.serializer(), v)
        )
        assertEquals(v, decoded)
    }

    @Test
    fun rejectsOutOfRangeProbability() {
        assertFailsWith<IllegalArgumentException> { VoidAnomaly(probability = 1.5f) }
    }

    @Test
    fun rejectsInvertedFloorRange() {
        assertFailsWith<IllegalArgumentException> { VoidAnomaly(floorBarsMin = 3f, floorBarsMax = 1f) }
    }

    @Test
    fun defaultsAreShipValues() {
        val v = VoidAnomaly()
        assertEquals(0.04f, v.probability)
        assertEquals(0.05f, v.floorLevel)
        assertEquals(1.0f, v.rampDownBars)
        assertEquals(1.0f, v.floorBarsMin)
        assertEquals(2.0f, v.floorBarsMax)
        assertEquals(1.5f, v.rampUpBars)
        assertEquals(0.5f, v.ghostIntensity)
    }
}
