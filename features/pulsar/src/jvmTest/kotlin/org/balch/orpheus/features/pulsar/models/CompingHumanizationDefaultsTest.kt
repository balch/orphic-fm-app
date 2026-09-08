package org.balch.orpheus.features.pulsar.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the shape of the [CompingHumanization] defaults: they may vary RHYTHM but never PITCH.
 *
 * `apply_humanization` in `pulsar_comping.h` treats the four probabilities very differently.
 * `drop` and `ghost` add and remove hits — a bar stops repeating identically, and the harmony
 * is untouched. `octaveJump` shifts a step +/-12 semitones and `extension` adds 2 or 5, so both
 * rewrite pitch, they can land on the same step in one pass, and all four are multiplied by
 * `complexity` — which climbs through an arrangement, so the effect is strongest in late
 * sections. A vibe with several arpeggiating chordal tracks then gets several voices scattering
 * at once.
 *
 * Shipping non-zero pitch defaults did exactly that to Velvet Leash's three chordal tracks
 * (2026-09-07). Rhythmic variation is a safe floor for a track whose author omitted the block;
 * pitch variation is an authored choice and stays opt-in.
 */
class CompingHumanizationDefaultsTest {

    private val defaults = CompingHumanization()

    @Test
    fun `pitch-altering defaults are zero`() {
        assertEquals(
            0.0f,
            defaults.octaveJumpProbability,
            "octaveJumpProbability shifts a step +/-12 semitones. A non-zero DEFAULT scatters " +
                "every chordal track whose author omitted the humanization block, and stacks " +
                "across tracks. Set it per-vibe instead.",
        )
        assertEquals(
            0.0f,
            defaults.extensionProbability,
            "extensionProbability adds 2 or 5 semitones on top of any octave jump. Same reasoning " +
                "as octaveJumpProbability: authored per vibe, never inherited.",
        )
    }

    @Test
    fun `rhythmic defaults are a gentle non-zero floor`() {
        // The original bug this guards against is the opposite one: an all-zero default repeats a
        // byte-identical bar forever, which reads as a sequencer rather than a player.
        assertTrue(
            defaults.dropProbability > 0.0f && defaults.dropProbability <= 0.25f,
            "dropProbability should be a gentle floor, was ${defaults.dropProbability}",
        )
        assertTrue(
            defaults.ghostProbability > 0.0f && defaults.ghostProbability <= 0.25f,
            "ghostProbability should be a gentle floor, was ${defaults.ghostProbability}",
        )
    }

    @Test
    fun `an omitted comping block inherits those same defaults`() {
        // TrackRole.Chordal() with no comping argument is the exact shape that made Velvet Leash's
        // PAR and STR tracks pick this up; assert the path rather than trusting it.
        val chordal = TrackRole.Chordal()
        assertEquals(0.0f, chordal.comping.humanization.octaveJumpProbability)
        assertEquals(0.0f, chordal.comping.humanization.extensionProbability)
        assertTrue(chordal.comping.humanization.dropProbability > 0.0f)
    }
}
