package org.balch.orpheus.features.ai.tools

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drift guard for the guide's mix prose: every model field the Mix recipes and the Mixing section
 * name must exist in the generated schema or in TRACK_OVERRIDES_NOTE.
 */
class VibeGuideMixingTest {

    private val guide = runBlocking { loadStaticGuide() }

    private val recipes = guide.substringAfter("### Mix (").substringBefore("## 11. Mixing")
    private val mixing = guide.substringAfter("## 11. Mixing").substringBefore("## 12.")

    // Mix tool result fields, not vibe fields; the DJ app's GuideToolNamesTest checks them against the tools.
    private val toolFields = setOf("lufsShort", "lufsIntegrated", "pastKneePct", "relLu", "subPct", "lowPct", "trackLabels")

    private val schema = generateVibeSchemaJson()

    private fun isVibeField(name: String) =
        schema.contains("\"$name\":") || Regex("""\b$name\b""").containsMatchIn(TRACK_OVERRIDES_NOTE)

    /** Backticked identifiers plus every camelCase word ("dB" is a unit, not a field). */
    private fun namedFields(text: String): Set<String> =
        (Regex("`([A-Za-z]+)`").findAll(text) + Regex("""\b[a-z]+[A-Z][A-Za-z]*\b""").findAll(text))
            .map { it.groupValues.last() }
            .filterNot { it == "dB" }
            .toSet()

    @Test
    fun `Mixing is section 11, after the recipes and before the invariants`() {
        val recipesAt = guide.indexOf("## 10. Translation recipes")
        val mixingAt = guide.indexOf("## 11. Mixing")
        val invariantsAt = guide.indexOf("## 12. Invariants")
        assertTrue(recipesAt in 0 until mixingAt, "Mixing should follow the recipes")
        assertTrue(mixingAt < invariantsAt, "Invariants should follow Mixing as section 12")
        assertTrue(guide.contains("### Mix ("), "section 10 lost its Mix recipes")
    }

    @Test
    fun `Mixing names the core mix fields`() {
        listOf("levelDb", "pan", "reverbSend", "delaySend", "reverbBrightness", "volume", "noteRangeLow", "noteRangeHigh")
            .forEach { assertTrue(it in namedFields(mixing), "Mixing section no longer names `$it`") }
    }

    @Test
    fun `every field the mix prose names is a real vibe field`() {
        val named = namedFields(recipes) + namedFields(mixing)
        val unknown = named.filterNot { isVibeField(it) || it in toolFields }
        assertTrue(unknown.isEmpty(), "mix prose names fields the schema and trackOverrides note lack: $unknown")
    }

    @Test
    fun `the enum values the mix recipes name are in the schema`() {
        listOf("REGGAE_SKANK", "NEVER").forEach {
            assertTrue(recipes.contains(it), "precondition: the recipes name $it")
            assertTrue(schema.contains(it), "schema has no $it")
        }
    }

    @Test
    fun `section 7 and the trackOverrides note list pan, and the note lists morph`() {
        assertTrue(guide.contains("pan (glides there at 0.15 pan units per second)"), "section 7 list lost pan")
        assertTrue(Regex("""\bpan \(-1\.\.1; the track glides""").containsMatchIn(TRACK_OVERRIDES_NOTE), "note lost pan")
        assertTrue(Regex("""\bmorph,""").containsMatchIn(TRACK_OVERRIDES_NOTE), "note lost morph")
    }

    private val handover = guide.substringAfter("6. Hand the mix over:").substringBefore("### Gotchas")

    @Test
    fun `guide says to mix only with the Mixer open`() {
        listOf("only while that line says open", "MIXER_CLOSED", "Open Mixer", "mix_reset", "section_jump").forEach {
            assertTrue(Regex(it.split(" ").joinToString("""\s+""") { w -> Regex.escape(w) }).containsMatchIn(handover), "hand-over lost \"$it\"")
        }
        assertTrue(Regex("""when the mix tools are\s+available""", RegexOption.IGNORE_CASE).containsMatchIn(handover), "hand-over is not conditional")
    }

    @Test
    fun `guide mentions pins and skips and the wipe confirmation`() {
        listOf("PINNED", "USER_MOVED", "WIPE_UNCONFIRMED", "Clear All", "Keep my mix", "never ask them to keep their hands off")
            .forEach { assertTrue(Regex(it.split(" ").joinToString("""\s+""") { w -> Regex.escape(w) }).containsMatchIn(handover), "hand-over lost \"$it\"") }
    }

    @Test
    fun `guide no longer says tell me when you are back`() {
        listOf("Mixer tab", "when they are back", "tell you when", "when the user says they are back").forEach {
            assertTrue(!guide.contains(it, ignoreCase = true), "the guide still says \"$it\"")
        }
    }
}
