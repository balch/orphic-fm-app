package org.balch.orpheus.features.pulsar

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import kotlin.test.Test
import kotlin.test.assertEquals

class VibeSerializationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `DogHouse vibe round-trips through JSON unchanged`() {
        val original = DogHouseVibe().vibe
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Vibe>(encoded)
        assertEquals(original, decoded)
    }
}
