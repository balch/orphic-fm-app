package org.balch.orpheus.djapp.vibepresets

import kotlinx.serialization.json.Json
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.VibePreset
import org.balch.orpheus.core.preferences.VibePresetSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PresetBookTest {

    private val values = mapOf("mix.bass" to 0.75f)
    private val originals = mapOf("mix.bass" to 0.5f)

    private fun VibePresetSlot.add(name: String, atMs: Long = 1_000L, replaceId: Int? = null) =
        capture(name, atMs, values, originals, replaceId)

    private fun fullBook(): VibePresetSlot =
        (1..MAX_PRESETS).fold(VibePresetSlot()) { slot, n -> slot.add("P$n") }

    @Test
    fun captureAppendsAndSelects() {
        val slot = VibePresetSlot().add("First")

        assertEquals(listOf(1), slot.presets.map { it.id })
        assertEquals(1, slot.selectedId)
        assertEquals(2, slot.nextId)
        assertEquals("First", slot.selected?.name)
    }

    @Test
    fun fullBookRequiresReplaceId() {
        val slot = fullBook()
        assertEquals(true, slot.isFull)

        assertFailsWith<IllegalArgumentException> { slot.add("Sixth") }

        assertEquals(listOf(1, 2, 3, 4, 5), slot.presets.map { it.id })
    }

    @Test
    fun replaceKeepsPositionAndSelects() {
        val slot = fullBook().add("Fresh", atMs = 9_000L, replaceId = 2)

        assertEquals(MAX_PRESETS, slot.presets.size)
        assertEquals("Fresh", slot.presets[1].name)
        assertEquals(9_000L, slot.presets[1].capturedAtMs)
        assertEquals(slot.presets[1].id, slot.selectedId)
        assertEquals(7, slot.nextId)
    }

    @Test
    fun renameKeepsCaptureTime() {
        val slot = VibePresetSlot().add("Old", atMs = 4_000L).rename(1, "New")

        assertEquals("New", slot.presets.single().name)
        assertEquals(4_000L, slot.presets.single().capturedAtMs)
    }

    @Test
    fun deletingSelectedClearsSelection() {
        val slot = VibePresetSlot().add("A").add("B").delete(2)

        assertEquals(listOf(1), slot.presets.map { it.id })
        assertNull(slot.selectedId)
    }

    @Test
    fun deletingOtherKeepsSelection() {
        val slot = VibePresetSlot().add("A").add("B").delete(1)

        assertEquals(listOf(2), slot.presets.map { it.id })
        assertEquals(2, slot.selectedId)
    }

    @Test
    fun selectNullClearsSelection() {
        val slot = VibePresetSlot().add("A").select(null)

        assertNull(slot.selected)
        assertFalse(slot.isFull)
    }

    @Test
    fun oldPrefsJsonWithoutPresetFieldsDecodes() {
        val prefs = Json { ignoreUnknownKeys = true }.decodeFromString<AppPreferences>("{}")

        assertEquals(AppPreferences(), prefs)
        assertEquals(emptyMap(), prefs.vibePresets)
        assertFalse(prefs.vibePresetBarShown)
    }

    @Test
    fun presetRoundTripsThroughAppPreferencesJson() {
        val slot = VibePresetSlot(
            presets = listOf(VibePreset(3, "Warm", 1234L, values, originals)),
            selectedId = 3,
            nextId = 4,
        )
        val prefs = AppPreferences(vibePresets = mapOf("Stay Asleep" to slot), vibePresetBarShown = true)
        val json = Json { ignoreUnknownKeys = true }

        val decoded = json.decodeFromString<AppPreferences>(json.encodeToString(AppPreferences.serializer(), prefs))

        assertEquals(prefs, decoded)
    }
}
