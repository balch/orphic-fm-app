package org.balch.orpheus.features.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** Locks the archive filename contract: chronological prefix + filesystem-safe, non-colliding name. */
class AiVibeFileNameTest {

    @Test
    fun `sanitizes punctuation and spaces to underscores with a millis prefix`() {
        assertEquals(
            "1730000000000_Lysergic_Descent.json",
            aiVibeFileName("Lysergic Descent!", 1730000000000L),
        )
    }

    @Test
    fun `collapses runs of non-alphanumerics and trims leading or trailing underscores`() {
        assertEquals("5_Deep_Space.json", aiVibeFileName("  Deep — Space  ", 5L))
    }

    @Test
    fun `blank name falls back to vibe`() {
        assertEquals("42_vibe.json", aiVibeFileName("   ", 42L))
    }
}
