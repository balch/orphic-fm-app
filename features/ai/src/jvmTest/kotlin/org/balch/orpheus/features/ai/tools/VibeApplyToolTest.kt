package org.balch.orpheus.features.ai.tools

import org.balch.orpheus.features.ai.AgentErrorLog
import org.balch.orpheus.features.ai.AgentErrorSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.features.ai.NoOpAiVibeArchive
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.VibeCreateEventBus
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.CastProvenance
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.HalfLick
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TonalTension
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import org.balch.orpheus.features.pulsar.vibes.TremoloTideVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VibeApplyToolTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `valid vibe json decodes to the same vibe`() {
        val dh = DogHouseVibe().vibe
        val result = decodeVibe(json, json.encodeToString(dh))
        assertTrue(result.isSuccess)
        assertEquals(dh, result.getOrNull())
    }

    @Test
    fun `malformed json fails with a non-empty message`() {
        val result = decodeVibe(json, "{ this is not a vibe }")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.isNotBlank() == true)
    }

    @Test
    fun `unknown album enum value coerces to the default instead of failing`() {
        // The agent commonly writes the vibe TITLE into the `album` enum field. Because `album`
        // has a default (STEALTH), the tool's Json must coerce the unknown value rather than crash.
        val dh = DogHouseVibe().vibe
        val mangled = vibeApplyJson.encodeToString(dh)
            .replace("\"album\":\"${dh.album.name}\"", "\"album\":\"Heartland Echoes\"")
        // The default album decodes back to itself, so a replace that missed would pass unseen.
        assertTrue("\"album\":\"Heartland Echoes\"" in mangled, "precondition: the album field was not mangled")
        val result = decodeVibe(vibeApplyJson, mangled)
        assertTrue(result.isSuccess, "decode should tolerate an unknown album: ${result.exceptionOrNull()?.message}")
        assertEquals(Album.STEALTH, result.getOrThrow().album)
    }

    @Test
    fun `a vibe using section trackOverrides round-trips through the apply path`() {
        // trackOverrides is excluded from the generated schema, but it must still WORK end-to-end:
        // the agent can emit it and the apply path must decode it. TremoloTide uses it heavily.
        val vibe = TremoloTideVibe().vibe
        assertTrue(
            vibe.arrangement?.sections?.any { it.trackOverrides != null } == true,
            "precondition: the test vibe should actually use trackOverrides",
        )
        val result = decodeVibe(vibeApplyJson, vibeApplyJson.encodeToString(vibe))
        assertTrue(result.isSuccess, "decode failed: ${result.exceptionOrNull()?.message}")
        assertTrue(
            result.getOrThrow().arrangement?.sections?.any { it.trackOverrides != null } == true,
            "trackOverrides did not survive the round-trip",
        )
    }

    @Test
    fun `unknown comping style falls back to the default instead of failing`() {
        // CompingStyle is a sealed class, so the agent inventing a non-existent style (e.g.
        // ORGAN_PAD at $.tracks[].role.comping.style) throws a polymorphic-scope error.
        // coerceInputValues only rescues unknown *enum* values; a polymorphicDefaultDeserializer
        // makes an unknown *sealed subtype* fall back to the default (PAD) rather than crash.
        val encoded = vibeApplyJson.encodeToString(ChordComping(style = CompingStyle.FUNK_STABS))
        val mangled = encoded.replace("CompingStyle.FUNK_STABS", "CompingStyle.ORGAN_PAD")
        val decoded = vibeApplyJson.decodeFromString<ChordComping>(mangled)
        assertEquals(CompingStyle.PAD, decoded.style)
    }

    @Test
    fun `a known comping style still decodes exactly`() {
        // The fallback must not swallow valid styles — round-trip a real one.
        val encoded = vibeApplyJson.encodeToString(ChordComping(style = CompingStyle.GOSPEL_STABS))
        val decoded = vibeApplyJson.decodeFromString<ChordComping>(encoded)
        assertEquals(CompingStyle.GOSPEL_STABS, decoded.style)
    }

    @Test
    fun `unknown comping style inside a trackOverride object falls back to PAD`() {
        // TrackSectionOverride.compingStyle lives in Section.trackOverrides, which is excluded from the
        // generated schema (Int map keys), so the object-form polymorphic fallback is its only safety net.
        // TRACK_OVERRIDES_NOTE steers the agent to this object form (a bare string would throw).
        val tso = vibeApplyJson.decodeFromString<TrackSectionOverride>(
            """{"density":0.3,"compingStyle":{"type":"org.balch.orpheus.features.pulsar.models.CompingStyle.ORGAN_PAD"}}""",
        )
        assertEquals(CompingStyle.PAD, tso.compingStyle)
    }

    @Test
    fun `unknown anomaly type degrades to an auto-inert void instead of failing`() {
        // The guide documents only the "void", "lick" and "wah" anomaly types, so an invented one
        // (e.g. {"type":"sweep"}) is a rare-hallucination path — but it must degrade, not crash
        // the whole apply into the retry loop. InertVoidAnomalyDeserializer decodes it as a
        // VoidAnomaly with probability zeroed: still declared (the manual anomaly trigger can
        // fire it) but never auto-firing, so a hallucinated type cannot add surprise 4% voids.
        val vibe = DogHouseVibe().vibe.copy(anomalies = listOf(VoidAnomaly(probability = 0.5f)))
        val mangled = vibeApplyJson.encodeToString(vibe)
            .replace("\"type\":\"void\"", "\"type\":\"sweep\"")
        val result = decodeVibe(vibeApplyJson, mangled)
        assertTrue(result.isSuccess, "decode failed: ${result.exceptionOrNull()?.message}")
        val anomaly = result.getOrThrow().anomalies.single()
        assertTrue(anomaly is VoidAnomaly, "expected the VoidAnomaly fallback, got $anomaly")
        assertEquals(0f, anomaly.probability, "fallback void must be auto-inert (probability 0)")
    }

    @Test
    fun `known anomaly types still decode exactly`() {
        // The fallback must not swallow the real subtypes. Round-trip one of each documented type.
        // The wah is here because the guide now teaches the agent to author it: "wah" is a
        // registered sealed subtype, so it must decode to a real WahAnomaly with its authored
        // probability intact, NOT get quietly rewritten into an auto-inert void by the fallback.
        val lick = Lick(listOf(LickStep(0, 0.5f)), loopLength = 8)
        val vibe = DogHouseVibe().vibe.copy(
            lick = lick,
            anomalies = listOf(
                VoidAnomaly(probability = 0.2f),
                LickAnomaly(lick = lick, chance = 0.3f),
                WahAnomaly(probability = 0.05f),
            ),
        )
        val result = decodeVibe(vibeApplyJson, vibeApplyJson.encodeToString(vibe))
        assertTrue(result.isSuccess, "decode failed: ${result.exceptionOrNull()?.message}")
        assertEquals(vibe.anomalies, result.getOrThrow().anomalies)
    }

    @Test
    fun `unknown lick mode falls back to None`() {
        // LickMode is the other enum-like sealed type the agent authors; same fallback applies.
        val encoded = vibeApplyJson.encodeToString<LickMode>(LickMode.Squash)
        val mangled = encoded.replace("LickMode.Squash", "LickMode.GHOST")
        val decoded = vibeApplyJson.decodeFromString<LickMode>(mangled)
        assertEquals(LickMode.None, decoded)
    }

    @Test
    fun `a pre-enum boolean halfLick decodes as JAM or OFF`() {
        // Saves archived before HalfLick became an enum carry "halfLick":true/false. The AI
        // edition lists only saves that decode, so without this they vanish from the list.
        val dh = DogHouseVibe().vibe
        val vibe = dh.copy(tension = (dh.tension ?: TensionProfile()).copy(tonal = TonalTension(halfLick = HalfLick.JAM)))
        val encoded = vibeApplyJson.encodeToString(vibe)
        assertTrue("\"halfLick\":\"JAM\"" in encoded, "precondition: halfLick was not encoded")

        val jam = decodeVibe(vibeApplyJson, encoded.replace("\"halfLick\":\"JAM\"", "\"halfLick\":true"))
        assertEquals(HalfLick.JAM, jam.getOrThrow().tension?.tonal?.halfLick)

        val off = decodeVibe(vibeApplyJson, encoded.replace("\"halfLick\":\"JAM\"", "\"halfLick\":false"))
        assertEquals(HalfLick.OFF, off.getOrThrow().tension?.tonal?.halfLick)
    }

    @Test
    fun `smart quotes are sanitized before decoding`() {
        val dh = DogHouseVibe().vibe
        val withSmartQuotes = json.encodeToString(dh).replace('"', '“')
        // sanitizeVibeJson should turn curly quotes back into straight quotes so decode succeeds
        val result = decodeVibe(json, withSmartQuotes)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `a name containing an escaped quote round-trips instead of being corrupted`() {
        // Regression test: sanitizeVibeJson used to unconditionally strip every backslash-escaped
        // quote, which corrupted a LEGITIMATE escaped quote inside a field value (not just AI
        // double-escaping) into invalid JSON. decodeVibe's fallback design fixes this — see its doc.
        val vibe = DogHouseVibe().vibe.copy(name = "Midnight \"Echo\" Run")
        val result = decodeVibe(vibeApplyJson, vibeApplyJson.encodeToString(vibe))
        assertTrue(result.isSuccess, "decode failed: ${result.exceptionOrNull()?.message}")
        assertEquals("Midnight \"Echo\" Run", result.getOrThrow().name)
    }

    @Test
    fun `a blob double-escaped as if still wrapped in its own string literal still decodes`() {
        // Preserves the original intent of the escaped-quote fallback: some models emit the JSON
        // as if it were still a string literal (every quote backslash-escaped). The fallback in
        // decodeVibe recovers that as a last resort, only after a direct decode fails.
        val dh = DogHouseVibe().vibe
        val doubled = vibeApplyJson.encodeToString(dh).replace("\"", "\\\"")
        val result = decodeVibe(vibeApplyJson, doubled)
        assertTrue(result.isSuccess, "decode failed: ${result.exceptionOrNull()?.message}")
        assertEquals(dh, result.getOrNull())
    }

    @Test
    fun applyBudgetRefusesFourth() = runBlocking {
        val pulsar = ApplyOnlyPulsar(DogHouseVibe().vibe)
        val tool = VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), emptySet(), AgentErrorLog()).tool
        val names = listOf("Ember One", "Ember Two", "Ember Three", "Ember Four")
        val results = names.map { n ->
            tool.execute(VibeApplyArgs(vibeApplyJson.encodeToString(DogHouseVibe().vibe.copy(name = n))))
        }
        assertEquals(listOf(true, true, true, false), results.map { it.success })
        assertEquals(listOf(false, false, false, true), results.map { it.budgetSpent })
        assertEquals(TurnBudget.SPENT, results.last().message)
        assertEquals(names.take(3), pulsar.applied.map { it.name }, "the refused apply never reaches the engine")
    }

    @Test
    fun aRejectedVibeDoesNotSpendTheApplyBudget() = runBlocking {
        val pulsar = ApplyOnlyPulsar(DogHouseVibe().vibe)
        val tool = VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), emptySet(), AgentErrorLog()).tool
        repeat(TurnBudget.MAX_REJECTED_APPLIES) {
            val rejected = tool.execute(VibeApplyArgs("{ not a vibe }"))
            assertFalse(rejected.success)
            assertFalse(rejected.budgetSpent, "a rejected vibe is fixable, not a spent budget")
        }
        assertTrue(tool.execute(VibeApplyArgs(vibeApplyJson.encodeToString(DogHouseVibe().vibe))).success)
    }

    @Test
    fun rejectionsPastTheCapTellTheAgentToStop() = runBlocking {
        val pulsar = ApplyOnlyPulsar(DogHouseVibe().vibe)
        val budget = TurnBudget()
        val tool = VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), budget, emptySet(), AgentErrorLog()).tool
        val results = List(TurnBudget.MAX_REJECTED_APPLIES + 1) { tool.execute(VibeApplyArgs("")) }
        assertTrue(results.none { it.success })
        assertEquals(List(TurnBudget.MAX_REJECTED_APPLIES) { false } + true, results.map { it.budgetSpent })
        assertTrue("vibeJson was empty" in results.first().message, results.first().message)
        budget.reset()
        assertFalse(tool.execute(VibeApplyArgs("")).budgetSpent, "a new turn gets fresh fixes")
    }

    @Test
    fun aRejectedVibeLandsInTheAgentErrorLog() = runBlocking {
        val log = AgentErrorLog()
        val tool = VibeApplyTool({ ApplyOnlyPulsar(DogHouseVibe().vibe) }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), emptySet(), log).tool
        assertFalse(tool.execute(VibeApplyArgs("{ not a vibe }")).success)
        val entry = log.entries.value.single()
        assertEquals(AgentErrorSource.VIBE, entry.source)
        assertEquals("pulsar_apply_vibe", entry.tool)
        assertTrue(entry.message.startsWith("Could not build vibe"), entry.message)
        assertTrue(tool.execute(VibeApplyArgs(vibeApplyJson.encodeToString(DogHouseVibe().vibe))).success)
        assertEquals(1, log.entries.value.size, "a successful apply logs nothing")
    }

    @Test
    fun aCastBlockSurvivesTheApplyAndOldJsonWithoutOneStillLoads() = runBlocking {
        val pulsar = ApplyOnlyPulsar(DogHouseVibe().vibe)
        val tool = VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), emptySet(), AgentErrorLog()).tool
        val cast = castVibe(VibeCastArgs(mode = "ALL_ROLLED", seed = 77L, styleTags = listOf("funk")), savedMode = null)
        val composed = vibeApplyJson.decodeFromString<Vibe>(cast.vibeJson).copy(name = "Porch Light")
        val result = tool.execute(VibeApplyArgs(vibeApplyJson.encodeToString(composed)))
        assertTrue(result.success, result.message)
        assertEquals(CastProvenance(77L, "ALL_ROLLED", listOf("funk")), pulsar.applied.single().cast)

        val old = vibeApplyJson.encodeToString(DogHouseVibe().vibe).replace(",\"cast\":null", "")
        assertTrue("\"cast\"" !in old, "precondition: the JSON predates the cast block")
        assertEquals(DogHouseVibe().vibe, decodeVibe(vibeApplyJson, old).getOrThrow())
    }

    @Test
    fun emptyGuardSetApplies() = runBlocking {
        val pulsar = ApplyOnlyPulsar(DogHouseVibe().vibe)
        val tool = VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), emptySet(), AgentErrorLog()).tool
        val result = tool.execute(VibeApplyArgs(vibeApplyJson.encodeToString(DogHouseVibe().vibe.copy(name = "Open Road"))))
        assertTrue(result.success, result.message)
        assertNull(result.refusal)
        assertEquals(listOf("Open Road"), pulsar.applied.map { it.name })
    }

    @Test
    fun aGuardRefusalStopsTheApply() = runBlocking {
        val pulsar = ApplyOnlyPulsar(DogHouseVibe().vibe)
        val guards = setOf(VibeApplyGuard { null }, VibeApplyGuard { "Ask the user first." })
        val tool = VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), guards, AgentErrorLog()).tool
        val result = tool.execute(VibeApplyArgs(vibeApplyJson.encodeToString(DogHouseVibe().vibe)))
        assertFalse(result.success)
        assertEquals("Ask the user first.", result.message)
        assertEquals(VibeApplyGuard.REFUSAL, result.refusal)
        assertTrue(pulsar.applied.isEmpty())
    }

    @Test
    fun guardRefusalsDoNotSpendTheApplyBudget() = runBlocking {
        val pulsar = ApplyOnlyPulsar(DogHouseVibe().vibe)
        var asked = 0
        val guard = VibeApplyGuard { if (++asked <= TurnBudget.MAX_APPLIES) "Ask the user first." else null }
        val tool = VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), setOf(guard), AgentErrorLog()).tool
        val json = vibeApplyJson.encodeToString(DogHouseVibe().vibe)
        repeat(TurnBudget.MAX_APPLIES) { assertEquals(VibeApplyGuard.REFUSAL, tool.execute(VibeApplyArgs(json)).refusal) }
        val applied = tool.execute(VibeApplyArgs(json))
        assertTrue(applied.success, applied.message)
        assertEquals(1, pulsar.applied.size)
    }

    @Test
    fun guardsHearOfTheApplyBeforeItLands() = runBlocking {
        val pulsar = ApplyOnlyPulsar(DogHouseVibe().vibe)
        val heard = mutableListOf<String>()
        val guard = object : VibeApplyGuard {
            override suspend fun refusal(): String? = null
            override suspend fun beforeApply() {
                heard += "before, ${pulsar.applied.size} applied"
            }
            override fun applyFailed() {
                heard += "failed"
            }
        }
        val tool = VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), setOf(guard), AgentErrorLog()).tool
        assertTrue(tool.execute(VibeApplyArgs(vibeApplyJson.encodeToString(DogHouseVibe().vibe))).success)
        assertEquals(listOf("before, 0 applied"), heard)
        // A vibe that never decodes never reaches the guard's hooks.
        assertFalse(tool.execute(VibeApplyArgs("{")).success)
        assertEquals(listOf("before, 0 applied"), heard)
    }

    /** Records applies; the rest of Pulsar stays at its stub defaults. */
    private class ApplyOnlyPulsar(first: Vibe) : PulsarFeature {
        val applied = mutableListOf<Vibe>()
        override val vibeFlow = MutableStateFlow(first)
        override val arrangementStateFlow = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
        override val stateFlow = MutableStateFlow(PulsarUiState(vibe = first))
        override val actions = PulsarPanelActions()
        override fun applyVibe(vibe: Vibe) {
            applied += vibe
            vibeFlow.value = vibe
        }
    }
}
