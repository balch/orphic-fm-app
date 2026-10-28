package org.balch.orpheus.djapp.vibepresets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetChangesTest {

    @Test
    fun unknownKeysAreIgnoredOnApply() {
        val changes = changesBetween(
            reference = mapOf("gone.key" to 0.1f, "mix.bass" to 0.5f),
            current = mapOf("gone.key" to 0.9f, "mix.bass" to 0.5f),
        )

        assertTrue(changes.isEmpty())
    }

    @Test
    fun missingKeysAreNotChanges() {
        val changes = changesBetween(
            reference = mapOf("mix.bass" to 0.5f),
            current = mapOf("mix.keys" to 0.9f),
        )

        assertTrue(changes.isEmpty())
    }

    @Test
    fun smallDriftIsNotAChange() {
        val changes = changesBetween(mapOf("mix.bass" to 0.5f), mapOf("mix.bass" to 0.5005f))

        assertTrue(changes.isEmpty())
    }

    @Test
    fun changesFollowFieldOrder() {
        val changes = changesBetween(
            reference = mapOf("pulsar.bpm" to 92f, "mix.perc" to 0.1f, "horn.speed" to 0.2f),
            current = mapOf("pulsar.bpm" to 100f, "mix.perc" to 0.2f, "horn.speed" to 0.3f),
        )

        assertEquals(
            listOf(PresetField.MIX_PERC, PresetField.HORN_SPEED, PresetField.PULSAR_BPM),
            changes.map { it.field },
        )
    }

    @Test
    fun chipFormatsBpmAsInteger() {
        val chip = PresetChange(PresetField.PULSAR_BPM, 92f, 104f).chipText()

        assertEquals("PULSAR: BPM 92→104", chip)
    }

    @Test
    fun chipFormatsFaderAsPercent() {
        val chip = PresetChange(PresetField.MIX_BASS, 0.75f, 0.6f).chipText()

        assertEquals("MIX: BASS 75%→60%", chip)
    }

    @Test
    fun chipFormatsRootAsNoteName() {
        val chip = PresetChange(PresetField.PULSAR_ROOT, 0f, 10f).chipText()

        assertEquals("PULSAR: ROOT C→A#", chip)
    }

    @Test
    fun chipFormatsMuteAsOnOff() {
        val chip = PresetChange(PresetField.TRACKS_3_MUTE, 0f, 1f).chipText()

        assertEquals("MIX: T3 MUTE off→on", chip)
    }

    @Test
    fun chipFormatsBrakeAsOnOff() {
        val chip = PresetChange(PresetField.HORN_BRAKE, 1f, 0f).chipText()

        assertEquals("HORN: BRAKE on→off", chip)
    }

    @Test
    fun chipFormatsScaleAsInteger() {
        val chip = PresetChange(PresetField.PULSAR_SCALE, 1f, 3f).chipText()

        assertEquals("PULSAR: SCALE 1→3", chip)
    }
}
