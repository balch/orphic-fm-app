package org.balch.orpheus.features.pulsar

import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.FillType
import org.balch.orpheus.features.pulsar.models.SectionInversion
import org.balch.orpheus.features.pulsar.models.VoicingType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the comping enums in `ChordComping.kt` in lockstep with their C++ twins in
 * `orpheus_unit_pulsar.h`.
 *
 * These cross the wire as bare integers: `PulsarFeature` sends `arpMode.ordinal`,
 * `arpDirection.ordinal` and `sectionInversion.ordinal`, and `CompingStyle` sends its
 * hand-written `engineId`. Nothing validates the far end — `orpheus_unit_pulsar.cpp`
 * static_casts the int straight to the enum class. So inserting a member in the middle
 * of either side silently re-maps every vibe's comping: a PAD becomes FUNK_STABS, an UP
 * arp becomes DOWN. Nothing crashes and nothing logs; the vibes just play wrong.
 *
 * Paths are relative to the module directory (Gradle's test working dir). The header is
 * declared as a `jvmTest` input in build.gradle.kts, without which a header-only edit
 * would leave this task UP-TO-DATE and skip the guard entirely.
 */
class PulsarCompingEnumParityTest {

    private val pulsarHeader = File("../../liborpheus_dsp/src/orpheus_unit_pulsar.h")

    /**
     * Parses `enum class NAME : uint8_t { MEMBER = N, ... };` into name -> value, in
     * declaration order. Handles both the single-line and multi-line forms.
     */
    private fun cppEnum(name: String, expectedSize: Int): Map<String, Int> {
        assertTrue(pulsarHeader.exists(), "missing C++ source ${pulsarHeader.absolutePath}")
        val body = Regex("""enum\s+class\s+$name\s*:\s*\w+\s*\{([^}]*)}""")
            .find(pulsarHeader.readText())
            ?.groupValues?.get(1)
        requireNotNull(body) { "could not find `enum class $name` in ${pulsarHeader.name}" }

        // Strip line comments so a stray `=` in prose cannot register as a member.
        val cleaned = body.lineSequence().joinToString("\n") { it.substringBefore("//") }
        val members = Regex("""(\w+)\s*=\s*(\d+)""")
            .findAll(cleaned)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }

        // Exact count, never `> 0`: a regex that matches nothing must fail loudly rather
        // than yield an empty map that compares equal to nothing and trivially passes.
        require(members.size == expectedSize) {
            "expected $expectedSize members in `enum class $name`, parsed ${members.size}: " +
                "${members.keys}. Update this test deliberately when the enum grows."
        }
        return members
    }

    /** Asserts a Kotlin enum's names and ordinals match the C++ enum exactly, in order. */
    private fun assertOrdinalParity(
        cppName: String,
        kotlinNames: List<String>,
        note: String,
    ) {
        val cpp = cppEnum(cppName, kotlinNames.size)
        assertEquals(
            kotlinNames,
            cpp.keys.toList(),
            "$cppName members must match the Kotlin enum by name AND order. $note",
        )
        kotlinNames.forEachIndexed { ordinal, name ->
            assertEquals(
                ordinal,
                cpp[name],
                "$cppName.$name is ${cpp[name]} in C++ but ordinal $ordinal in Kotlin. $note",
            )
        }
    }

    private val wireNote =
        "The value crosses the wire as a bare int and is static_cast on the C++ side, " +
            "so a mismatch silently re-maps every vibe instead of failing."

    @Test
    fun `ArpDirection matches ArpDirectionId`() {
        assertOrdinalParity(
            "ArpDirectionId",
            ArpDirection.entries.map { it.name },
            wireNote,
        )
    }

    @Test
    fun `ArpMode matches ArpModeId`() {
        assertOrdinalParity("ArpModeId", ArpMode.entries.map { it.name }, wireNote)
    }

    @Test
    fun `FillType matches FillTypeId`() {
        assertOrdinalParity("FillTypeId", FillType.entries.map { it.name }, wireNote)
    }

    @Test
    fun `SectionInversion matches SectionInversionId`() {
        assertOrdinalParity(
            "SectionInversionId",
            SectionInversion.entries.map { it.name },
            wireNote,
        )
    }

    @Test
    fun `VoicingType matches its C++ twin`() {
        // Mirrored on both sides but not yet read by either — guarding it now keeps the
        // pair honest until whatever wires it up arrives.
        assertOrdinalParity("VoicingType", VoicingType.entries.map { it.name }, wireNote)
    }

    // Listed rather than reflected: kotlin-reflect is not on the test classpath, and each
    // entry is a `data object`, whose generated toString() is its own simple name.
    // Residual gap: a NEW CompingStyle object missing from this list is caught only if the
    // C++ enum also gained a member (the set difference below), not on its own.
    private val kotlinStyles = listOf(
        CompingStyle.PAD,
        CompingStyle.FUNK_STABS,
        CompingStyle.ROCK_DOWNBEATS,
        CompingStyle.SKA_UPSTROKES,
        CompingStyle.BLUES_SHUFFLE,
        CompingStyle.JAZZ_COMP,
        CompingStyle.REGGAE_SKANK,
        CompingStyle.GOSPEL_STABS,
    )

    @Test
    fun `CompingStyle engineId matches CompingStyleId`() {
        // C++ carries a CUSTOM = 3 placeholder with no Kotlin counterpart, so this pair
        // is matched by NAME rather than by position.
        val cpp = cppEnum("CompingStyleId", expectedSize = 9)
        val kotlin = kotlinStyles.associate { it.toString() to it.engineId }

        require(kotlin.size == 8) {
            "expected 8 CompingStyle objects, found ${kotlin.size}: ${kotlin.keys}"
        }

        assertEquals(
            setOf("CUSTOM"),
            cpp.keys - kotlin.keys,
            "CUSTOM is the only C++ CompingStyleId without a Kotlin object. A new " +
                "unmatched name means the sealed class and the enum have diverged.",
        )
        assertEquals(
            emptySet(),
            kotlin.keys - cpp.keys,
            "every CompingStyle object needs a CompingStyleId of the same name",
        )
        kotlin.forEach { (name, engineId) ->
            assertEquals(
                cpp[name],
                engineId,
                "CompingStyle.$name.engineId must equal CompingStyleId::$name. $wireNote",
            )
        }
    }
}
