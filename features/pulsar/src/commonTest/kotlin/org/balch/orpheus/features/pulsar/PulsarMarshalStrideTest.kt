package org.balch.orpheus.features.pulsar

import org.balch.orpheus.features.pulsar.anonmalies.StormAnomaly
import org.balch.orpheus.features.pulsar.models.DuckingProfile
import org.balch.orpheus.features.pulsar.models.ScratchEffect
import org.balch.orpheus.features.pulsar.models.SectionWeather
import org.balch.orpheus.features.pulsar.models.StrikeEffect
import org.balch.orpheus.features.pulsar.models.TapeStopEffect
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the wire row width / arity / slot order for the four storm-weather banks
 * (trans-fx, storm anomaly, section weather, breathe) against the Kotlin types the
 * marshal in [PulsarViewModel.pushArrangement] reads from. These are pure-Kotlin
 * pins — the C++-side stride constants are cross-checked separately in
 * `PulsarSectionLimitsTest` (jvmTest), which can parse the C++ headers.
 *
 * Mirrors the WahParams.FIELDS pattern (see `WahAnomalyTest.fieldsConstantMatchesSerializableArity`):
 * anchor a wire constant to the serialized arity so a field addition without a matching
 * marshal update is a red test, not a silently-scrambled bank.
 */
class PulsarMarshalStrideTest {

    // --- trans_fx_data_$i: 24 rows x 7 fields, type_id 1/2/3 ---

    @Test
    fun transitionFxWireRowShapeMatchesBankSize() {
        assertEquals(7, TransitionFxWire.ROW_FIELDS)
        assertEquals(24, TransitionFxWire.MAX_ROWS)
        assertEquals(168, TransitionFxWire.BANK_SIZE)
        assertEquals(
            TransitionFxWire.ROW_FIELDS * TransitionFxWire.MAX_ROWS, TransitionFxWire.BANK_SIZE,
            "BANK_SIZE must derive from ROW_FIELDS * MAX_ROWS, not a separate literal",
        )
    }

    @Test
    fun transitionFxTypeIdsMirrorCppTransFxType() {
        // Must match TransFxType in liborpheus_dsp/src/pulsar_transition_fx.h verbatim
        // (cross-checked against the header itself in PulsarSectionLimitsTest).
        assertEquals(0, TransitionFxWire.TYPE_NONE)
        assertEquals(1, TransitionFxWire.TYPE_SCRATCH)
        assertEquals(2, TransitionFxWire.TYPE_TAPE_STOP)
        assertEquals(3, TransitionFxWire.TYPE_STRIKE)
    }

    @Test
    fun transitionFxEdgeSentinelsAreDistinctAndNegative() {
        // Must match kTransFxEdgeAny / kTransFxEdgeEntry in pulsar_transition_fx.h
        // (cross-checked against the header itself in PulsarSectionLimitsTest). Negative so
        // they can never collide with a real edge slot, distinct so an exit row and an entry
        // row fire on opposite ends of a flip.
        assertEquals(-1f, TransitionFxWire.EDGE_ANY)
        assertEquals(-2f, TransitionFxWire.EDGE_ENTRY)
    }

    @Test
    fun scratchAndTapeStopEffectsCarryOnlyMs() {
        // Both marshal to a single p0=ms float (offset_bars is hardcoded 0 in the marshal,
        // not read from either class). A second field here means the marshal silently
        // drops it, so pin the arity down to exactly one.
        val scratchDescriptor = ScratchEffect.serializer().descriptor
        assertEquals(1, scratchDescriptor.elementsCount)
        assertEquals("ms", scratchDescriptor.getElementName(0))

        val tapeStopDescriptor = TapeStopEffect.serializer().descriptor
        assertEquals(1, tapeStopDescriptor.elementsCount)
        assertEquals("ms", tapeStopDescriptor.getElementName(0))
    }

    @Test
    fun strikeEffectFieldOrderMatchesTransFxRowMarshal() {
        // The marshal reads intensity->p0, distance->p1, delayMs->p2, offsetBars->the row's
        // own offset_bars slot (NOT positional against this descriptor) — pin the four
        // field names/order so a reorder of StrikeEffect is caught here first.
        val descriptor = StrikeEffect.serializer().descriptor
        assertEquals(4, descriptor.elementsCount)
        assertEquals(
            listOf("intensity", "distance", "offsetBars", "delayMs"),
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) },
        )
    }

    // --- pulsar_section_data slots 21-25: SectionWeather ---

    @Test
    fun sectionWeatherFieldOrderMatchesWeatherSlotMarshal() {
        // Marshal order is rain(21), rumble(22), strikeChance(23), distance(24),
        // rainLevel(25) — positional against this descriptor. rainLevel is APPENDED
        // rather than sat beside rain: the wire grows at the end, and inserting it in
        // the middle would silently reassign every slot after it.
        val descriptor = SectionWeather.serializer().descriptor
        assertEquals(5, descriptor.elementsCount)
        assertEquals(
            listOf("rain", "rumble", "strikeChance", "distance", "rainLevel"),
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) },
        )
    }

    // --- storm_data_$i: 6 floats (5 StormAnomaly fields + declared flag) ---

    @Test
    fun stormAnomalyFieldOrderAndBankSizeMatchMarshal() {
        val descriptor = StormAnomaly.serializer().descriptor
        assertEquals(5, descriptor.elementsCount)
        assertEquals(
            listOf("probability", "durationBarsMin", "durationBarsMax", "intensity", "distance"),
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) },
        )
        // storm_data_$i is [probability, durationBarsMin, durationBarsMax, intensity,
        // distance, declared] — the 5 class fields plus one synthesized declared flag.
        assertEquals(
            6, descriptor.elementsCount + 1,
            "storm_data bank size should be StormAnomaly's arity plus the declared flag",
        )
    }

    // --- track_ducking_$i: 6 DuckingProfile fields + declared flag ---

    @Test
    fun duckingProfileFieldOrderAndWireStrideMatchMarshal() {
        // The marshal writes these positionally: volumeReduction(0), densityReduction(1),
        // ghostReduction(2), fillSuppression(3), simplify(4), reverbBoost(5) — then the
        // synthesized declared flag at 6. A reorder here silently scrambles the bank.
        val descriptor = DuckingProfile.serializer().descriptor
        assertEquals(
            listOf(
                "volumeReduction", "densityReduction", "ghostReduction",
                "fillSuppression", "simplify", "reverbBoost",
            ),
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) },
        )
        assertEquals(
            descriptor.elementsCount + 1, DuckingProfile.WIRE_FIELDS,
            "WIRE_FIELDS should be DuckingProfile's arity plus the declared flag",
        )
    }

    @Test
    fun duckingProfileDefaultsMatchTheEngineDuckConstants() {
        // These are kUnauthoredDuck* in pulsar_band_solo.h — the duck the engine applies to a
        // track that authored nothing. Keeping the Kotlin defaults equal to them is what
        // makes `DuckingProfile()` a no-op rather than a silent deepening of every band vibe.
        val d = DuckingProfile()
        assertEquals(0.18f, d.volumeReduction)
        assertEquals(0.2f, d.densityReduction)
        assertEquals(0.35f, d.ghostReduction)
        assertEquals(0.35f, d.fillSuppression)
        assertEquals(true, d.simplify)
        assertEquals(0.1f, d.reverbBoost)
    }

    // --- breathe: 3 trailing slots on the section_track_* per-track override family ---

    @Test
    fun trackSectionOverrideTrailingFieldsAreBreathe() {
        // breatheBars/breatheFloor/breatheTimbreSpan must be the LAST three declared
        // fields, in this order — the marshal writes them as trailing slots and any
        // future field inserted before them (rather than appended after) would still
        // pass a plain "contains" check but silently reorder nothing here since the
        // marshal reads them by name, not position. This test instead pins that the
        // three names exist with this exact spelling, matching PulsarFeature's writer.
        val descriptor = TrackSectionOverride.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertEquals(
            listOf("breatheBars", "breatheFloor", "breatheTimbreSpan"),
            names.takeLast(3),
            "breathe fields must be the trailing three on TrackSectionOverride, matching " +
                "the doc comment's \"trailing slots\" contract",
        )
    }
}
