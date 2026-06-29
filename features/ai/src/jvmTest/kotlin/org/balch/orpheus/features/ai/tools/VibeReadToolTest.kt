package org.balch.orpheus.features.ai.tools

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibeReadToolTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val dogHouse = DogHouseVibe().vibe

    @Test
    fun `current returns the live vibe json`() {
        val result = resolveVibeJson(json, "current", dogHouse, listOf(dogHouse), listOf("Dog House"))
        assertTrue(result.success)
        assertEquals(dogHouse, json.decodeFromString<Vibe>(result.vibeJson))
    }

    @Test
    fun `known name returns that vibe json`() {
        val result = resolveVibeJson(json, "Dog House", dogHouse, listOf(dogHouse), listOf("Dog House"))
        assertTrue(result.success)
        assertEquals(dogHouse, json.decodeFromString<Vibe>(result.vibeJson))
    }

    @Test
    fun `unknown name fails and lists available names`() {
        val result = resolveVibeJson(json, "Nope", dogHouse, listOf(dogHouse), listOf("Dog House"))
        assertFalse(result.success)
        assertTrue(result.message.contains("Dog House"))
    }

    @Test
    fun `template read with the apply config round-trips back through the apply path`() {
        // The agent's core loop is pulsar_get_vibe (encode) -> edit -> pulsar_apply_vibe (decode).
        // VibeReadTool now encodes with vibeApplyJson, so the template's class discriminator can never
        // drift from what the decoder expects. Exercise the real configs end-to-end (DogHouse mixes
        // Percussive/Melodic/Chordal track roles, so this covers the TrackRole polymorphism that broke).
        val template = resolveVibeJson(vibeApplyJson, "current", dogHouse, listOf(dogHouse), listOf("Dog House"))
        assertTrue(template.success)
        val decoded = decodeVibe(vibeApplyJson, template.vibeJson)
        assertTrue(decoded.isSuccess, "template did not round-trip through apply: ${decoded.exceptionOrNull()?.message}")
        assertEquals(dogHouse, decoded.getOrThrow())
    }
}
