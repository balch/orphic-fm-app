package org.balch.orpheus.djapp.ai.licklab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LickLabKeyHintTest {
    private val major = listOf(0, 2, 4, 5, 7, 9, 11)

    // Degrees 0..13 in C major from C4 = 60, so index i is degree i.
    private val keys = (0..13).map { StripKey(LickPitch.degreeToMidi(it, 60, major), "", it, black = false) }

    private val ui = LickLabUiState(
        baseMidi = 60,
        scale = major,
        chordRootDegree = 0,
        nextChordRootDegree = 4,
        changeBeat = 10.0,
        hintWindowBeats = 0.5,
        hintMode = HintMode.Root,
        lastPlayedMidi = 72,
    )

    @Test
    fun `no change beat gives no hint`() {
        assertNull(keyHint(ui.copy(changeBeat = null), keys))
    }

    @Test
    fun `an unknown next chord gives no hint`() {
        assertNull(keyHint(ui.copy(nextChordRootDegree = -1), keys))
    }

    @Test
    fun `an empty scale gives no hint`() {
        assertNull(keyHint(ui.copy(scale = emptyList()), keys))
    }

    @Test
    fun `the hint lights the next chord tones and aims at its root`() {
        val hint = keyHint(ui, keys)!!
        assertEquals(10.0, hint.changeBeat)
        assertEquals(0.5, hint.windowBeats)
        // Degree 4 chord: degrees 4 6 8 and 11 13 (1 and 1+7).
        assertEquals(setOf(1, 4, 6, 8, 11, 13), hint.tones)
        // Root mode from C5 = 72: G4 = 67 (index 4) is 5 away and G5 = 79 (index 11) is 7.
        assertEquals(4, hint.preferred)
        assertEquals(11, keyHint(ui.copy(lastPlayedMidi = 76), keys)!!.preferred)
    }
}
