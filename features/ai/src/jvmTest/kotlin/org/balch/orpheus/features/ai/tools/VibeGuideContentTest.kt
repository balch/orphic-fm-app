package org.balch.orpheus.features.ai.tools

import kotlinx.coroutines.runBlocking
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.ai.composer.CastMode
import org.balch.orpheus.features.ai.composer.DRONE_BEDS
import org.balch.orpheus.features.ai.composer.EngineMirror
import org.balch.orpheus.features.ai.composer.Part
import org.balch.orpheus.features.ai.composer.STYLE_TAGS
import org.balch.orpheus.features.ai.composer.VOICE_PALETTE
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The guide teaches the cast-first workflow, names only real engines and fields, and never sends the agent to a template. */
class VibeGuideContentTest {

    private val guide = runBlocking { loadStaticGuide() }
    private val built = runBlocking { buildGuide(emptyList()) }
    private val schema = generateVibeSchemaJson()
    private val engineIds = OrpheusEngineId.entries.map { it.name }.toSet()

    private val casting = section("## A. Casting", "## B. ")
    private val families = section("## 3. Tracks", "Where the knobs mean something else")
    private val mixPass = section("### The mix pass", "### Gotchas")

    private fun section(from: String, to: String): String {
        val start = guide.indexOf(from)
        val end = guide.indexOf(to, start + 1)
        check(start >= 0 && end > start) { "guide lost the span $from .. $to" }
        return guide.substring(start, end)
    }

    private fun count(text: String, phrase: String) = Regex(Regex.escape(phrase)).findAll(text).count()

    /** Section 3's family lines, continuation lines joined: part name to the engine leading each "/" item. */
    private val familyLines: Map<String, List<String>> = Regex("""(?m)^- ([a-z][^:\n]*): ((?:.+)(?:\n  .+)*)""")
        .findAll(families)
        .associate { m -> m.groupValues[1] to m.groupValues[2].replace(Regex("""\s+"""), " ").split("/").map { it.trim().substringBefore(' ') } }

    // Uppercase prose: shouted words, meter labels, units and roman numerals. Not engines.
    private val prose = setOf(
        "NOT", "ONE", "TWO", "OUT", "IS", "DO", "DOES", "CAN", "BE", "AT", "ARE", "ONLY", "MOST", "LAST", "EVERY",
        "WOULD", "WITHOUT", "NOTHING", "REPLACES", "POSITIVE", "GENERATED", "EMPTY", "STANDING", "SOLOIST",
        "MEMBERS", "ROLE", "TRACK", "ENGINE", "PITCH", "BAND", "LICK", "VOID", "WAH", "DECAY", "DEEP",
        "JSON", "MIDI", "LU", "LUFS", "EDM", "SPACE", "MOOD", "BED", "LFO", "LPG",
        "KICK", "PERC", "HIHAT", "KEYS", "TEXT", "FX", "IV", "VI", "VII", "MAX_LICK_POOL",
    )

    // song_reference output values (PluckGrid, RhythmSource in :apps:djapp:ai's songref), which this module can't see.
    private val songReferenceValues = setOf(
        "DOWNBEATS", "EIGHTHS", "EIGHTH_OFFBEATS", "SIXTEENTHS", "SIXTEENTH_OFFBEATS", "TRIPLETS", "MIXED", "SPACING",
    )

    private fun paragraphs(text: String) = text.split(Regex("""\n\s*\n"""))

    private val mixTool = Regex("""\b(?:mix|audio|section|ports?)_[a-z_]+\b|\btransport\b""")

    @Test
    fun guideTeachesCasting() {
        assertTrue(casting.contains("pulsar_cast_vibe"), "the casting section never names pulsar_cast_vibe")
        CastMode.entries.forEach { assertTrue(casting.contains(it.name), "the casting section never names mode ${it.name}") }
        assertTrue(casting.contains("seed"), "the casting section never mentions the seed")
        STYLE_TAGS.forEach { assertTrue(casting.contains(it), "the casting section never names style tag $it") }
        Part.entries.forEach {
            val part = it.name.lowercase()
            assertTrue(Regex("""\b$part\b""").containsMatchIn(casting), "the casting section never names pin role $part")
        }
        val pinRoles = PinArg.serializer().descriptor.getElementAnnotations(1).toString()
        Part.entries.forEach { assertTrue(it.name.lowercase() in pinRoles, "PinArg.role's description lacks ${it.name.lowercase()}") }
        assertTrue(guide.contains("## B. Music theory guardrails"), "guide lost the theory section")
        assertTrue(guide.contains("## C. Drone beds and expressive voices"), "guide lost the beds section")
        DRONE_BEDS.forEach { assertTrue(guide.contains("'${it.name}'"), "guide never names bed '${it.name}'") }
    }

    @Test
    fun guideNamesMixToolsOnlyConditionally() {
        val named = paragraphs(built).filter { mixTool.containsMatchIn(it) }
        assertTrue(named.isNotEmpty(), "precondition: the guide names the mix tools")
        val bare = named.filterNot { p -> p.contains("when", ignoreCase = true) && p.contains("available") }
        assertTrue(bare.isEmpty(), "paragraphs name mix tools without 'when ... available':\n" + bare.joinToString("\n---\n"))
    }

    @Test
    fun everyEngineIdInGuideExists() {
        val tokens = Regex("""\b[A-Z][A-Z0-9_]*[A-Z0-9]\b""").findAll(guide).map { it.value }.toSet()
        val notes = Regex("""[A-G]\d""")
        val known = prose + songReferenceValues + CastMode.entries.map { it.name }
        val unknown = tokens.filterNot { t ->
            t in engineIds || t in known || notes.matches(t) || Regex("""\b$t\b""").containsMatchIn(schema)
        }
        assertTrue(unknown.isEmpty(), "uppercase tokens that are neither engine ids nor known words: $unknown")

        // Section 3's family lines ("- pad: ENS string machine / STR bowed string / ...") lead each item with an engine.
        assertEquals(Part.entries.map { it.name.lowercase() }.toSet(), familyLines.keys.map { it.substringBefore(' ') }.toSet(), "section 3's parts")
        val badIds = familyLines.values.flatten().filterNot { it in engineIds }
        assertTrue(badIds.isEmpty(), "section 3 family items lead with non-engines: $badIds")

        val inFamilies = familyLines.values.flatten().toSet()
        val cast = VOICE_PALETTE.map { it.engine.name } +
            DRONE_BEDS.flatMap { b -> b.tracks.flatMap { listOf(it.engineEdm.engineId.name, it.engineSpace.engineId.name) } }
        val missing = cast.toSet() - inFamilies
        assertTrue(missing.isEmpty(), "section 3 lacks engines the cast plays: $missing")
    }

    @Test
    fun mixPassStatesTheRealBudget() {
        val steps = mixPass.split(Regex("""\n(?=\d\. )"""))
        val caps = steps.single { Regex("""\d+\s+mix edits""").containsMatchIn(it) }
        assertTrue(caps.contains("${TurnBudget.MAX_APPLIES} applies"), "the caps step lost the apply cap: $caps")
        assertTrue(Regex("""${TurnBudget.MAX_MIX_EDITS}\s+mix edits""").containsMatchIn(caps), "the caps step lost the mix-edit cap: $caps")
        assertTrue(mixPass.contains("mix_capture first"), "the mix pass lost the capture before a re-apply")
        listOf(caps, steps.single { "Mixer tab" in it }).forEach {
            assertTrue(Regex("""when the mix tools are\s+available""", RegexOption.IGNORE_CASE).containsMatchIn(it), "step is not conditional:\n$it")
        }
    }

    @Test
    fun correctedFactsStateTheEngine() {
        assertTrue(!Regex("""rhythmDensity\s*=\s*[A-Z]""").containsMatchIn(guide), "a recipe writes rhythmDensity as a name; it is a Float")

        val hold = section("- Hold:", "\n- LPG:")
        assertTrue(hold.contains("only on tracks 5-7") && hold.contains("only engineEdm"), "hold lost its track or slot limits:\n$hold")

        assertEquals(1, count(guide, "above 0.6"), "state the EDM/SPACE switch once")
        val switch = section("Energy above 0.6", "The two must differ")
        listOf("below 0.4", "soloists included", "a soloist stays on EDM there", "same-engine pair keeps its last slot")
            .forEach { assertTrue(Regex(it.split(" ").joinToString("""\s+""") { w -> Regex.escape(w) }).containsMatchIn(switch), "the switch sentence lost \"$it\":\n$switch") }

        assertEquals(1, count(guide, "VOWEL_FOF: harmonics picks the vowel"), "state the VOWEL_FOF mapping once")
        assertTrue(!Regex("""VOWEL_FOF[^.]*morph picks the vowel""").containsMatchIn(guide), "VOWEL_FOF's vowel is harmonics, not morph")
    }

    // The notch and track 7 gate as EngineMirror (held to the C++) has them, and what lifts a track.
    @Test
    fun notchAdviceStatesTheEngine() {
        listOf("section `volume` boost", "fires mostly at the energy extremes").forEach {
            assertTrue(it !in guide, "the guide still says \"$it\"")
        }
        val notch = section("- The mid-energy notch:", "\n- Track 7, the FX slot")
        val floor = "${EngineMirror.NOTCH_FLOOR_FROM}-${EngineMirror.NOTCH_FLOOR_TO}"
        listOf("energy $floor", "Chordal role", "plays the vibe's lick", "lickMode Fill or Squash", "textureLine false", "0..1")
            .forEach { assertTrue(it in notch.replace(Regex("\\s+"), " "), "the notch bullet lost \"$it\":\n$notch") }
        val gate = section("- Track 7, the FX slot", "\n- The EDM and space slots")
        val lowEdge = ((1f - EngineMirror.FX_LOW_EDGE) * 100).roundToInt() / 100f
        listOf("below energy $lowEdge", "over ${EngineMirror.FX_HIGH_ENERGY}", "complexity over ${EngineMirror.FX_HIGH_COMPLEXITY}", "whatever its role, lick or volume", "textureLine true")
            .forEach { assertTrue(it in gate.replace(Regex("\\s+"), " "), "the track 7 bullet lost \"$it\":\n$gate") }
        val lick = section("### Lick / riff", "### Mix")
        assertTrue("lifted out of the mid-energy notch" in lick.replace(Regex("\\s+"), " "), "section 10 never teaches the lick lift")
    }

    @Test
    fun expressiveRangesMatchTheModel() {
        // Each "field (lo-hi" in section C must be the model's own require() bounds.
        listOf<Triple<String, (Float) -> Unit, Float>>(
            Triple("vibratoDepth", { v -> OrpheusEngine(OrpheusEngineId.SPK, vibratoDepth = v) }, 1f),
            Triple("vibratoRate", { v -> OrpheusEngine(OrpheusEngineId.SPK, vibratoRate = v) }, 0.1f),
            Triple("pitchRise", { v -> OrpheusEngine(OrpheusEngineId.SPK, pitchRise = v) }, 1f),
        ).forEach { (field, build, step) ->
            val m = Regex("""$field \((\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)""").find(guide) ?: error("guide never states $field's range")
            val (lo, hi) = m.destructured.toList().map(String::toFloat)
            build(lo); build(hi)
            assertFailsWith<IllegalArgumentException>("$field below $lo") { build(lo - step) }
            assertFailsWith<IllegalArgumentException>("$field above $hi") { build(hi + step) }
        }
    }

    // Section C's rise range is the model's require(), and every grainFx bed voice keeps under its ceiling.
    @Test
    fun riseRangeAndGrainCeilingMatchTheCode() {
        val flat = guide.replace(Regex("\\s+"), " ")
        assertTrue("harmonicsRise and morphRise (-1..1)" in flat, "section C lost the rise range")
        listOf<(Float) -> Unit>(
            { v -> OrpheusEngine(OrpheusEngineId.SPK, harmonicsRise = v) },
            { v -> OrpheusEngine(OrpheusEngineId.SPK, morphRise = v) },
        ).forEach { build ->
            build(-1f); build(1f)
            assertFailsWith<IllegalArgumentException> { build(-1.01f) }
            assertFailsWith<IllegalArgumentException> { build(1.01f) }
        }
        val ceiling = Regex("""noteRangeHigh at (\d+) or under""").find(flat)?.groupValues?.get(1)?.toInt() ?: error("guide lost the grainFx ceiling")
        assertEquals(77, ceiling, "the grainFx ceiling is F5, MIDI 77")
        assertTrue("(MIDI $ceiling)" in flat, "the guide's F5 note and its ceiling disagree")
        val grainVoices = DRONE_BEDS.flatMap { b -> b.tracks.flatMap { listOf(it.engineEdm, it.engineSpace) }.filter { it.grainFx }.map { b.name to it } }
        assertTrue(grainVoices.isNotEmpty(), "precondition: a bed uses grainFx")
        grainVoices.forEach { (bed, e) -> assertTrue(e.noteRangeHigh <= ceiling, "bed '$bed' ${e.engineId} grains up to ${e.noteRangeHigh}, over $ceiling") }
    }

    @Test
    fun noTemplateLanguage() {
        val text = built.lowercase()
        listOf(
            "closest-fitting vibe as your starting template", "edit a template", "starting template",
            "pick the closest", "closest existing vibe", "you edit an existing vibe", "template's trim",
        ).forEach { assertTrue(it !in text, "guide still says \"$it\"") }
        assertTrue(built.contains("Shipped vibes, for ideas only, never a template"), "catalog header not updated")
    }

    @Test
    fun everyFieldTheNewSectionsNameIsReal() {
        val newSections = section("## A. Casting", "## 1. The frame")
        // Cast tool arguments and result fields, not vibe fields.
        val toolFields = setOf("styleTags", "vibeJson", "pasteId", "sameAs", "phraseSlots")
        val named = (Regex("`([A-Za-z]+)`").findAll(newSections) + Regex("""\b[a-z]+[A-Z][A-Za-z0-9]*\b""").findAll(newSections))
            .map { it.groupValues.last() }.toSet()
        val unknown = named.filterNot {
            it == "dB" || schema.contains("\"$it\":") || it in toolFields || Regex("""\b$it\b""").containsMatchIn(TRACK_OVERRIDES_NOTE)
        }
        assertTrue(unknown.isEmpty(), "sections A-D name fields the schema lacks: $unknown")
    }
}
