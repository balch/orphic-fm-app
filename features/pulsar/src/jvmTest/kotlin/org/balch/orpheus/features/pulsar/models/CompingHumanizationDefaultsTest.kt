package org.balch.orpheus.features.pulsar.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the shape of the [CompingHumanization] defaults.
 *
 * The rule: **an inherited default may REMOVE what the author placed. It may never ADD notes
 * or change pitch.** Only `dropProbability` is subtractive, so only `dropProbability` carries
 * a non-zero default; the other three are authored per vibe.
 *
 * The three that stay 0, and why, from `apply_humanization` in `pulsar_comping.h`:
 *  - `ghostProbability` fires on the *empty* steps, cloning the anchor onto them. On a sparse
 *    style that is not humanization, it is a different part: REGGAE_SKANK is two hits and
 *    fourteen rests, so a 0.15 ghost adds roughly two notes per bar and fills in the very gaps
 *    that make it a skank.
 *  - `octaveJumpProbability` shifts a step +/-12 semitones.
 *  - `extensionProbability` adds 2 or 5 on top of any jump.
 *
 * All four are scaled by `complexity`, which climbs through an arrangement, so an inherited
 * value is loudest exactly where a section is already busiest.
 *
 * Both halves of this were shipped bugs. Non-zero pitch defaults scattered Velvet Leash's three
 * arpeggiating tracks; a non-zero ghost default then filled in Bell Tolls' skank, whose author
 * had deliberately named the other three fields and left ghost out (2026-09-07/08).
 */
class CompingHumanizationDefaultsTest {

    private val defaults = CompingHumanization()

    @Test
    fun `only the subtractive probability carries a default`() {
        assertTrue(
            defaults.dropProbability > 0.0f && defaults.dropProbability <= 0.25f,
            "dropProbability thins hits the author already placed, so it is the one safe " +
                "floor against a byte-identical bar. Was ${defaults.dropProbability}",
        )
        assertEquals(
            0.0f,
            defaults.ghostProbability,
            "ghostProbability ADDS hits on the steps the author left empty. On a sparse style " +
                "that rewrites the part rather than humanizing it — it filled in Bell Tolls' " +
                "two-hit skank. Author it per vibe.",
        )
        assertEquals(
            0.0f,
            defaults.octaveJumpProbability,
            "octaveJumpProbability shifts a step +/-12 semitones. A non-zero DEFAULT scatters " +
                "every chordal track whose author omitted the field, and stacks across tracks.",
        )
        assertEquals(
            0.0f,
            defaults.extensionProbability,
            "extensionProbability adds 2 or 5 semitones on top of any octave jump. Same " +
                "reasoning: authored per vibe, never inherited.",
        )
    }

    @Test
    fun `a partially specified block still inherits the omitted fields`() {
        // The miss that broke Bell Tolls: the audit checked whether a humanization block
        // EXISTED, not which fields inside it were named. Naming three fields silently
        // inherits the fourth.
        val partial = CompingHumanization(
            dropProbability = 0.06f,
            octaveJumpProbability = 0.04f,
            extensionProbability = 0.08f,
        )
        assertEquals(
            0.0f,
            partial.ghostProbability,
            "a block that names three fields must not pick up a ghost default for the fourth",
        )
    }

    @Test
    fun `an omitted comping block inherits those same defaults`() {
        // TrackRole.Chordal() with no comping argument is the exact shape that made Velvet
        // Leash's PAR and STR tracks pick this up; assert the path rather than trusting it.
        val chordal = TrackRole.Chordal()
        assertEquals(0.0f, chordal.comping.humanization.ghostProbability)
        assertEquals(0.0f, chordal.comping.humanization.octaveJumpProbability)
        assertEquals(0.0f, chordal.comping.humanization.extensionProbability)
        assertTrue(chordal.comping.humanization.dropProbability > 0.0f)
    }
}
