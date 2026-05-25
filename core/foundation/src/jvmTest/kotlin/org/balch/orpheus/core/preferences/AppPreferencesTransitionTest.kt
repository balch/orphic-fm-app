package org.balch.orpheus.core.preferences

import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.TransitionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPreferencesTransitionTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default transition is TAPE at 1000ms`() {
        val prefs = AppPreferences()
        assertEquals(TransitionStyle.TAPE, prefs.pulsarTransitionDefault.style)
        assertEquals(1000, prefs.pulsarTransitionDefault.handoffMs)
    }

    @Test
    fun `legacy JSON without pulsarTransitionDefault decodes with default`() {
        val legacy = """{"pulsarSongEndingEnabled":true}"""
        val prefs = json.decodeFromString(AppPreferences.serializer(), legacy)
        assertEquals(TransitionStyle.TAPE, prefs.pulsarTransitionDefault.style)
    }

    @Test
    fun `round-trip preserves user-set transition`() {
        val original = AppPreferences().copy(
            pulsarTransitionDefault = AppPreferences().pulsarTransitionDefault.copy(
                style = TransitionStyle.TAPE,
                handoffMs = 250,
            )
        )
        val encoded = json.encodeToString(AppPreferences.serializer(), original)
        val decoded = json.decodeFromString(AppPreferences.serializer(), encoded)
        assertEquals(original.pulsarTransitionDefault, decoded.pulsarTransitionDefault)
    }
}
