package org.balch.orpheus.djapp.ai.licklab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LickHintTest {
    private val major = listOf(0, 2, 4, 5, 7, 9, 11)

    // Degrees -7..16 in C major from C4 = 60 upward, as the in-key strip lays them out.
    private val inKey = (-7..16).map { StripKey(LickPitch.degreeToMidi(it, 60, major), "", it, black = false) }
    private val chromatic = (0..17).map { o ->
        StripKey(60 + o, "", LickPitch.degreeOf(60 + o, 60, major), black = false)
    }

    private fun midiOf(keys: List<StripKey>, i: Int?) = i?.let { keys[it].midi }

    @Test
    fun `root picks the next root nearest the hand`() {
        // Next chord on degree 4 (G): G4 = 67 is nearest to 65.
        assertEquals(67, midiOf(inKey, LickHint.pick(HintMode.Root, inKey, 0, 4, 7, 65)))
    }

    @Test
    fun `near picks the nearest tone of the next chord`() {
        // G chord tones G B D: from 71 (B4) the nearest is B itself.
        assertEquals(71, midiOf(inKey, LickHint.pick(HintMode.Near, inKey, 0, 4, 7, 71)))
    }

    @Test
    fun `move skips tones already in the current chord`() {
        // C to G: G B D against C E G. B (71) is new, G is not.
        assertEquals(71, midiOf(inKey, LickHint.pick(HintMode.Move, inKey, 0, 4, 7, 67)))
    }

    @Test
    fun `move falls back to near when every tone is shared`() {
        // The same chord on both sides has no new tone, so the nearest chord tone.
        assertEquals(64, midiOf(inKey, LickHint.pick(HintMode.Move, inKey, 0, 0, 7, 64)))
    }

    @Test
    fun `a tie goes up`() {
        // C4 = 60 and C5 = 72 are both 6 from 66.
        assertEquals(72, midiOf(inKey, LickHint.pick(HintMode.Root, inKey, 0, 0, 7, 66)))
    }

    @Test
    fun `a chromatic strip ignores off scale keys`() {
        // Hand on C#4 (61): only scale tones count, and C4 is 1 away.
        assertEquals(60, midiOf(chromatic, LickHint.pick(HintMode.Near, chromatic, 0, 0, 7, 61)))
        assertEquals(64, midiOf(chromatic, LickHint.pick(HintMode.Near, chromatic, 0, 0, 7, 63)))
    }

    @Test
    fun `no next chord or no candidate is null`() {
        assertNull(LickHint.pick(HintMode.Near, inKey, 0, -1, 7, 60))
        assertNull(LickHint.pick(HintMode.Near, emptyList(), 0, 0, 7, 60))
        assertNull(LickHint.pick(HintMode.Near, inKey, 0, 0, 0, 60))
    }
}
