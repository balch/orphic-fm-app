package org.balch.orpheus.features.ai.tools

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import org.balch.orpheus.features.pulsar.vibes.TremoloTideVibe
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `unknown lick mode falls back to None`() {
        // LickMode is the other enum-like sealed type the agent authors; same fallback applies.
        val encoded = vibeApplyJson.encodeToString<LickMode>(LickMode.Squash)
        val mangled = encoded.replace("LickMode.Squash", "LickMode.GHOST")
        val decoded = vibeApplyJson.decodeFromString<LickMode>(mangled)
        assertEquals(LickMode.None, decoded)
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
}
