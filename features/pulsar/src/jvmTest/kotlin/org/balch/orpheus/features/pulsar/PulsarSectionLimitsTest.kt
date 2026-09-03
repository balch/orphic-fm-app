package org.balch.orpheus.features.pulsar

import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.HalfLick
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the Kotlin arrangement limits in lockstep with the C++ wire.
 *
 * The arrangement crosses into the audio thread through PREALLOCATED atomic arrays, so
 * a Kotlin-side cap larger than the C++ one does not crash — the routing layer in
 * `orpheus_engine_routing.cpp` bounds-checks each write and SILENTLY DROPS anything past
 * the end. The failure mode is a section that simply never arrives, which is close to
 * impossible to diagnose from the audio. This test makes the drift a build failure.
 *
 * Paths are relative to the module directory (Gradle's test working dir).
 */
class PulsarSectionLimitsTest {

    private val limitsHeader = File("../../liborpheus_dsp/src/pulsar_limits.h")
    private val engineHeader = File("../../liborpheus_dsp/src/orpheus_engine.h")
    private val pulsarHeader = File("../../liborpheus_dsp/src/orpheus_unit_pulsar.h")
    private val transFxHeader = File("../../liborpheus_dsp/src/pulsar_transition_fx.h")

    private fun constant(source: File, name: String): Int {
        assertTrue(source.exists(), "missing C++ source ${source.absolutePath}")
        // Signed: the trans-fx edge sentinels are negative.
        val re = Regex("""constexpr\s+int\s+$name\s*=\s*(-?\d+)""")
        val m = re.find(source.readText())
        requireNotNull(m) { "could not find `constexpr int $name` in ${source.name}" }
        return m.groupValues[1].toInt()
    }

    // Matches an `ENUM_NAME = N,` line inside a C++ `enum TypeName : int { ... };` body.
    private fun enumValue(source: File, enumName: String, memberName: String): Int {
        assertTrue(source.exists(), "missing C++ source ${source.absolutePath}")
        val body = source.readText()
            .substringAfter("enum $enumName")
            .substringBefore("};")
        val re = Regex("""$memberName\s*=\s*(\d+)""")
        val m = re.find(body)
        requireNotNull(m) { "could not find `$memberName` in enum $enumName (${source.name})" }
        return m.groupValues[1].toInt()
    }

    @Test
    fun `MAX_SECTIONS matches kMaxSections`() {
        assertEquals(
            constant(limitsHeader, "kMaxSections"),
            Arrangement.MAX_SECTIONS,
            "Arrangement.MAX_SECTIONS must equal kMaxSections in pulsar_limits.h. " +
                "A larger Kotlin cap does not fail loudly — extra sections are silently " +
                "dropped by the routing bounds checks.",
        )
    }

    @Test
    fun `MAX_SECTION_TRANSITIONS matches kMaxSectionTransitions`() {
        assertEquals(
            constant(limitsHeader, "kMaxSectionTransitions"),
            Arrangement.MAX_SECTION_TRANSITIONS,
            "This is the STRIDE both sides index the transition array with. If they " +
                "disagree, sections read each other's transition edges.",
        )
    }

    @Test
    fun `section wire arrays derive from kMaxSections, never a literal`() {
        // Regression guard for the original bug: 14 arrays each hardcoded `8`, so
        // bumping kMaxSections resized nothing and overran the buffers.
        val offenders = engineHeader.readLines()
            .withIndex()
            .filter { (_, line) -> line.contains("pulsar_section") && Regex("""\[\s*8\b""").containsMatchIn(line) }
            .map { (i, line) -> "orpheus_engine.h:${i + 1}: ${line.trim()}" }

        assertTrue(
            offenders.isEmpty(),
            "pulsar_section_* wire arrays must be sized from kMaxSections, not a literal:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `HalfLick ordinals mirror the C++ HalfLickMode wire values`() {
        // halfLick rides tension slot 7 as its ordinal, so the orders must match exactly.
        val body = pulsarHeader.readText()
            .substringAfter("enum class HalfLickMode")
            .substringBefore("};")
        val cpp = Regex("""(\w+)\s*=\s*(\d+)""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }

        assertEquals(
            HalfLick.entries.size, cpp.size,
            "HalfLick has ${HalfLick.entries.size} values but HalfLickMode has ${cpp.size}: $cpp",
        )
        HalfLick.entries.forEach { v ->
            assertEquals(v.ordinal, cpp[v.name], "HalfLick.${v.name} ordinal must match HalfLickMode::${v.name}")
        }
    }

    @Test
    fun `SECTION_DATA_FIELDS matches kSectionDataFields`() {
        assertEquals(
            constant(limitsHeader, "kSectionDataFields"),
            Arrangement.SECTION_DATA_FIELDS,
            "This is the per-section float stride pulsar_section_data (and its weather " +
                "slots 21-24) is written and read with. If they disagree, sections read " +
                "each other's fields. NOTE: pulsar_section_tension_data shares this " +
                "constant only for C++-side array sizing — its own indexing stride is " +
                "independently fixed at 21 and must NOT be pinned to this constant.",
        )
    }

    @Test
    fun `TransitionFxWire row shape matches pulsar_transition_fx h`() {
        assertEquals(
            constant(transFxHeader, "kTransFxRowFields"), TransitionFxWire.ROW_FIELDS,
            "trans_fx_data row width must match kTransFxRowFields",
        )
        assertEquals(
            constant(transFxHeader, "kMaxTransFxRows"), TransitionFxWire.MAX_ROWS,
            "trans_fx_data row cap must match kMaxTransFxRows",
        )
        // kTransFxBankSize is declared as `kMaxTransFxRows * kTransFxRowFields` in the
        // header, not a bare literal, so `constant()` can't parse it directly — the
        // header's own static_assert(kTransFxBankSize == 168, ...) pins its value, and
        // PulsarMarshalStrideTest (commonTest) pins BANK_SIZE == ROW_FIELDS * MAX_ROWS
        // on the Kotlin side, so this is the transitive check between the two.
        assertEquals(
            168, TransitionFxWire.ROW_FIELDS * TransitionFxWire.MAX_ROWS,
            "trans_fx_data bank size must match the header's static_assert(kTransFxBankSize == 168, ...)",
        )
    }

    @Test
    fun `TransitionFxWire edge sentinels mirror the C++ constants`() {
        // Both are negative `edge_idx` values the marshal writes and C++ dispatches on: -1
        // stages on any OUTGOING edge, -2 fires on any ARRIVAL. Drift here silently turns a
        // section's entry effects into exit effects (or into padding), with no bounds check
        // to catch it — the row simply matches the wrong seam.
        assertEquals(
            constant(transFxHeader, "kTransFxEdgeAny").toFloat(), TransitionFxWire.EDGE_ANY,
            "Section.exitEffects rows must carry kTransFxEdgeAny",
        )
        assertEquals(
            constant(transFxHeader, "kTransFxEdgeEntry").toFloat(), TransitionFxWire.EDGE_ENTRY,
            "Section.entryEffects rows must carry kTransFxEdgeEntry",
        )
    }

    @Test
    fun `TransitionFxWire type ids mirror C++ TransFxType`() {
        assertEquals(
            enumValue(transFxHeader, "TransFxType", "TRANS_FX_SCRATCH"), TransitionFxWire.TYPE_SCRATCH,
        )
        assertEquals(
            enumValue(transFxHeader, "TransFxType", "TRANS_FX_TAPE_STOP"), TransitionFxWire.TYPE_TAPE_STOP,
        )
        assertEquals(
            enumValue(transFxHeader, "TransFxType", "TRANS_FX_STRIKE"), TransitionFxWire.TYPE_STRIKE,
        )
    }
}
