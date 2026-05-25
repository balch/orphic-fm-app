package org.balch.orpheus.core.audio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionSpecSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `every TransitionStyle round-trips`() {
        for (style in TransitionStyle.entries) {
            val spec = TransitionSpec(style = style, handoffMs = 250)
            val encoded = json.encodeToString(TransitionSpec.serializer(), spec)
            val decoded = json.decodeFromString(TransitionSpec.serializer(), encoded)
            assertEquals(spec, decoded, "round-trip failed for $style")
        }
    }

    @Test
    fun `random pool serializes and deserializes`() {
        val spec = TransitionSpec(
            style = TransitionStyle.RANDOM,
            randomPool = listOf(TransitionStyle.FADE, TransitionStyle.CROSSFADE),
        )
        val encoded = json.encodeToString(TransitionSpec.serializer(), spec)
        val decoded = json.decodeFromString(TransitionSpec.serializer(), encoded)
        assertEquals(spec, decoded)
    }

    @Test
    fun `legacy JSON missing fields decodes with defaults`() {
        val legacy = """{"style":"FADE"}"""
        val decoded = json.decodeFromString(TransitionSpec.serializer(), legacy)
        assertEquals(TransitionStyle.FADE, decoded.style)
        assertEquals(null, decoded.handoffMs)
    }

    @Test
    fun `legacy JSON with retired outroBars and outroCurve decodes cleanly`() {
        // Pre-cleanup persisted specs may carry outroBars/outroCurve from when
        // the in-song outro fade was a thing. ignoreUnknownKeys lets them
        // deserialize without crashing — the values are simply dropped.
        val legacy = """{"style":"FADE","outroBars":0.5,"outroCurve":"LOG","handoffMs":350}"""
        val decoded = json.decodeFromString(TransitionSpec.serializer(), legacy)
        assertEquals(TransitionStyle.FADE, decoded.style)
        assertEquals(350, decoded.handoffMs)
    }
}
