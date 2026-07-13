package org.balch.orpheus.features.ai.tools

import kotlinx.serialization.decodeFromString
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.ChordStep
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins every JSON example embedded in the agent-facing guide prose (STATIC_GUIDE,
 * TRACK_OVERRIDES_NOTE) against the real apply-path decoder (`vibeApplyJson`), so the prose can
 * never silently drift from the schema again. Each constant is checked twice: it DECODES to its
 * real target type with the expected values, and it is actually CONTAINED in the shipped guide
 * text (guards the interpolation wiring, not just the constant's own content).
 */
class VibeGuideExamplesTest {

    @Test
    fun `bare-degree shorthand decodes to ChordSteps with no glide`() {
        val steps = vibeApplyJson.decodeFromString<List<ChordStep>>(VibeGuideExamples.CUSTOM_PROGRESSION_SHORTHAND)
        assertEquals(listOf(0, 3, 5, 6), steps.map { it.degree })
        assertTrue(steps.all { it.glideRate == 0f }, "shorthand form must carry no glide: $steps")
    }

    @Test
    fun `object form decodes to ChordSteps carrying the per-chord glideRate`() {
        val steps = vibeApplyJson.decodeFromString<List<ChordStep>>(VibeGuideExamples.CUSTOM_PROGRESSION_WITH_GLIDE)
        assertEquals(listOf(0, 3), steps.map { it.degree })
        assertEquals(0f, steps[0].glideRate)
        assertEquals(0.4f, steps[1].glideRate)
    }

    @Test
    fun `trackOverrides example decodes to Section trackOverrides map with chordFollow and density`() {
        val overrides = vibeApplyJson.decodeFromString<Map<Int, TrackSectionOverride>>(
            VibeGuideExamples.TRACK_OVERRIDES_EXAMPLE,
        )
        val track4 = overrides[4] ?: error("expected an override for track 4: $overrides")
        assertEquals(ChordFollow.FIXED, track4.chordFollow)
        assertEquals(0.3f, track4.density)
    }

    @Test
    fun `trackOverrides chordFollow-only example decodes with density left unset`() {
        val overrides = vibeApplyJson.decodeFromString<Map<Int, TrackSectionOverride>>(
            VibeGuideExamples.TRACK_OVERRIDES_CHORD_FOLLOW_EXAMPLE,
        )
        val track4 = overrides[4] ?: error("expected an override for track 4: $overrides")
        assertEquals(ChordFollow.FIXED, track4.chordFollow)
        assertNull(track4.density)
    }

    @Test
    fun `compingStyle example decodes to the polymorphic FUNK_STABS subtype`() {
        val style = vibeApplyJson.decodeFromString<CompingStyle>(VibeGuideExamples.COMPING_STYLE_EXAMPLE)
        assertEquals(CompingStyle.FUNK_STABS, style)
    }

    @Test
    fun `STATIC_GUIDE contains every customProgression and trackOverrides example it teaches`() {
        assertTrue(
            STATIC_GUIDE.contains(VibeGuideExamples.CUSTOM_PROGRESSION_SHORTHAND),
            "guide dropped the bare-degree shorthand example",
        )
        assertTrue(
            STATIC_GUIDE.contains(VibeGuideExamples.CUSTOM_PROGRESSION_WITH_GLIDE),
            "guide dropped the per-chord glide object-form example",
        )
        assertTrue(
            STATIC_GUIDE.contains(VibeGuideExamples.TRACK_OVERRIDES_CHORD_FOLLOW_EXAMPLE),
            "guide dropped the section-7 chordFollow pinning example",
        )
    }

    @Test
    fun `STATIC_GUIDE describes chordTransitionMatrix as the flat 49-value form, not nested rows`() {
        assertTrue(
            STATIC_GUIDE.contains("flat list of 49"),
            "guide should describe chordTransitionMatrix as a flat 49-value list, not nested rows",
        )
        assertFalse(
            STATIC_GUIDE.contains("7-element array of 7-element arrays"),
            "guide still describes chordTransitionMatrix with the wrong nested shape",
        )
    }

    @Test
    fun `STATIC_GUIDE ties per-chord glide to the chord-step object, not the track`() {
        assertTrue(
            STATIC_GUIDE.contains("glideRate on the chord-step object"),
            "per-chord glide bullet should attribute glideRate to the ChordStep object inside customProgression",
        )
    }

    @Test
    fun `TRACK_OVERRIDES_NOTE contains the trackOverrides and compingStyle examples it teaches`() {
        assertTrue(
            TRACK_OVERRIDES_NOTE.contains(VibeGuideExamples.TRACK_OVERRIDES_EXAMPLE),
            "TRACK_OVERRIDES_NOTE dropped its own trackOverrides example",
        )
        assertTrue(
            TRACK_OVERRIDES_NOTE.contains(VibeGuideExamples.COMPING_STYLE_EXAMPLE),
            "TRACK_OVERRIDES_NOTE dropped its own compingStyle example",
        )
    }
}
