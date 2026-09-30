package org.balch.orpheus.core.preferences

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VibePlaylistPrefsTest {
    // The same settings the desktop, Android and iOS repositories decode with.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun legacyJsonWithoutAPlaylistDecodesAsNull() {
        val prefs = json.decodeFromString(AppPreferences.serializer(), """{"domeSwiped":true}""")
        assertNull(prefs.vibePlaylist)
    }

    @Test
    fun aPlaylistRoundTrips() {
        val original = AppPreferences(vibePlaylist = VibePlaylistPrefs(listOf("B", "A"), setOf("A")))
        val encoded = json.encodeToString(AppPreferences.serializer(), original)
        assertEquals(original.vibePlaylist, json.decodeFromString(AppPreferences.serializer(), encoded).vibePlaylist)
    }

    // The album filter was dropped; a file saved with one must still load, or every setting resets.
    @Test
    fun aFileSavedWithTheOldAlbumFilterStillDecodes() {
        val prefs = json.decodeFromString(
            AppPreferences.serializer(),
            """{"vibePlaylist":{"order":["A","B"],"removed":["B"],"albums":["RIF"]}}""",
        )
        assertEquals(VibePlaylistPrefs(listOf("A", "B"), setOf("B")), prefs.vibePlaylist)
    }
}
