package org.balch.orpheus.features.ai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibeSchemaToolTest {

    @Test
    fun `schema discriminator matches the apply decoder for every sealed type`() {
        // Koog's schema generator defaults to classDiscriminator="kind", but pulsar_apply_vibe decodes
        // with kotlinx's default "type". If the schema advertises "kind", an agent that follows it emits
        // {"kind":...} and EVERY sealed type throws "Class discriminator was missing" on apply. The
        // schema MUST key its polymorphic discriminators with the same name the decoder reads.
        val schema = generateVibeSchemaJson()
        val decoderDiscriminator = vibeApplyJson.configuration.classDiscriminator
        assertEquals("type", decoderDiscriminator, "precondition: the apply decoder uses the kotlinx default")

        // No sealed subtype anywhere may still be keyed by "kind".
        assertFalse(
            schema.contains("\"kind\":{\"const\":"),
            "schema still advertises 'kind' as a polymorphic discriminator; the agent will emit keys the decoder can't read",
        )

        // Each of the 5 sealed families in the Vibe graph must use the decoder's discriminator.
        listOf("TrackRole", "CompingStyle", "LickMode", "SoloMode", "PitchEvolution").forEach { family ->
            val prefix = "org.balch.orpheus.features.pulsar.models.$family."
            assertTrue(
                schema.contains("\"$decoderDiscriminator\":{\"const\":\"$prefix"),
                "schema has no '$decoderDiscriminator'-keyed discriminator for sealed family $family",
            )
            assertFalse(
                schema.contains("\"kind\":{\"const\":\"$prefix"),
                "sealed family $family still uses the 'kind' discriminator in the schema",
            )
        }
    }

    @Test
    fun `generates a non-empty Vibe schema covering key enum fields`() {
        val schema = generateVibeSchemaJson()
        assertTrue(schema.length > 500, "schema unexpectedly short (${schema.length}): $schema")
        assertTrue(schema.contains("rootNote"), "schema missing rootNote")
        assertTrue(schema.contains("scaleType"), "schema missing scaleType")
        assertTrue(schema.contains("STEALTH"), "schema missing album enum vocabulary")
        // DRY guard: scales are generated from the ScaleType enum, so newly-added scales reach the
        // agent automatically. Locks that the recently-added blues scales are exposed in the schema.
        assertTrue(schema.contains("BLUES_PENTATONIC"), "schema missing the newly-added blues scales")
    }

    @Test
    fun `reference documents trackOverrides even though the schema omits it`() {
        // trackOverrides is excluded from the generated schema (Int map keys), so the agent must
        // still learn about it from the addendum.
        val reference = vibeSchemaReference()
        assertTrue(reference.contains("trackOverrides"), "reference missing trackOverrides guidance")
        assertTrue(reference.contains("chordFollow"), "reference missing a TrackSectionOverride field")
    }
}
