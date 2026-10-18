package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChordCompingRestrikeTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `restrikeOnChordChange defaults off`() {
        assertFalse(ChordComping().restrikeOnChordChange)
    }

    @Test
    fun `restrikeOnChordChange survives a serialization round trip`() {
        val c = ChordComping(arpMode = ArpMode.NEVER, restrikeOnChordChange = true)
        assertEquals(c, json.decodeFromString<ChordComping>(json.encodeToString(c)))
    }

    @Test
    fun `comping saved before the flag existed loads with it off`() {
        val old = """{"arpMode":"NEVER","arpSpeed":0.4}"""
        val c = json.decodeFromString<ChordComping>(old)
        assertEquals(ChordComping(arpMode = ArpMode.NEVER, arpSpeed = 0.4f), c)
        assertFalse(c.restrikeOnChordChange)
    }
}
