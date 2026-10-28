package org.balch.orpheus.djapp.vibepresets

import kotlin.test.Test
import kotlin.test.assertEquals

class PresetNamingTest {

    @Test
    fun noChangesIsOriginal() {
        assertEquals("Original", suggestPresetName(emptyList(), emptySet()))
    }

    @Test
    fun twoModulesJoinedByPlus() {
        // Horn moves 0.3 and bass 0.25, so the larger change names first.
        val changes = listOf(
            PresetChange(PresetField.MIX_BASS, 0.75f, 0.5f),
            PresetChange(PresetField.HORN_SPEED, 0.5f, 0.2f),
        )
        assertEquals("Slow Horn + Deep Bass", suggestPresetName(changes, emptySet()))
    }

    @Test
    fun onlyTopTwoModulesNamed() {
        val changes = listOf(
            PresetChange(PresetField.MIX_BASS, 0.75f, 0.5f),
            PresetChange(PresetField.HORN_SPEED, 0.5f, 0.2f),
            PresetChange(PresetField.PULSAR_MOOD, 0.5f, 0.55f),
        )
        val name = suggestPresetName(changes, emptySet())
        assertEquals(1, name.split(" + ").size - 1)
        assertEquals("Slow Horn + Deep Bass", name)
    }

    @Test
    fun bpmIsNormalizedByForty() {
        // 20 BPM is 0.5 normalized, which beats a 0.4 level move.
        val changes = listOf(
            PresetChange(PresetField.MIX_KEYS, 0.2f, 0.6f),
            PresetChange(PresetField.PULSAR_BPM, 100f, 120f),
        )
        assertEquals("Fast BPM + Bright Keys", suggestPresetName(changes, emptySet()))
    }

    @Test
    fun takenNameGetsNumberSuffix() {
        val changes = listOf(PresetChange(PresetField.PULSAR_BPM, 100f, 120f))
        assertEquals("Fast BPM 2", suggestPresetName(changes, setOf("Fast BPM")))
        assertEquals("Fast BPM 3", suggestPresetName(changes, setOf("Fast BPM", "Fast BPM 2")))
    }

    @Test
    fun wordsForEachKindOfField() {
        fun name(field: PresetField, from: Float, to: Float) =
            suggestPresetName(listOf(PresetChange(field, from, to)), emptySet())

        assertEquals("Quiet Keys", name(PresetField.MIX_KEYS, 0.8f, 0.4f))
        assertEquals("Heavy Drive", name(PresetField.MIX_DIST, 0.1f, 0.6f))
        assertEquals("Light Drive", name(PresetField.MIX_DIST, 0.6f, 0.1f))
        assertEquals("More Mood", name(PresetField.PULSAR_MOOD, 0.1f, 0.6f))
        assertEquals("Less Energy", name(PresetField.PULSAR_ENERGY, 0.9f, 0.4f))
        assertEquals("Muted T3", name(PresetField.TRACKS_3_MUTE, 0f, 1f))
        assertEquals("Solo T2", name(PresetField.TRACKS_2_SOLO, 0f, 1f))
        assertEquals("D Key", name(PresetField.PULSAR_ROOT, 0f, 2f))
    }
}
