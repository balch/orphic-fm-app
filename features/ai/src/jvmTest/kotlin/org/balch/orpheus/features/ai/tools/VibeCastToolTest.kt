package org.balch.orpheus.features.ai.tools

import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.features.ai.composer.CastMode
import org.balch.orpheus.features.ai.composer.CastRequest
import org.balch.orpheus.features.ai.composer.VibeCaster
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibeCastToolTest {

    private class FakePrefs(var stored: AppPreferences = AppPreferences()) : AppPreferencesRepository {
        override suspend fun load() = stored
        override suspend fun save(preferences: AppPreferences) { stored = preferences }
        override suspend fun update(transform: (AppPreferences) -> AppPreferences) { stored = transform(stored) }
    }

    private suspend fun cast(prefs: FakePrefs = FakePrefs(), args: VibeCastArgs) =
        VibeCastTool(prefs).tool.execute(args)

    @Test
    fun castReturnsSkeletonAndSeed() = runTest {
        val r = cast(args = VibeCastArgs(seed = 42L))
        assertTrue(r.ok, r.message)
        assertEquals(42L, r.seed)
        assertEquals(8, r.summary.size)
        val vibe = vibeApplyJson.decodeFromString<Vibe>(r.vibeJson)
        assertEquals(8, vibe.tracks.size)
        // The same seed rebuilds the same skeleton.
        assertEquals(r.vibeJson, cast(args = VibeCastArgs(seed = 42L)).vibeJson)
        assertEquals(
            vibeCastJson.encodeToString(VibeCaster.cast(CastRequest(CastMode.BED_FIXED, 42L)).vibe),
            r.vibeJson,
        )
    }

    // The skeleton leaves out defaults, and the apply path decodes it to the very same vibe.
    @Test
    fun compactSkeletonDecodesIdentically() {
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                val vibe = VibeCaster.cast(CastRequest(mode, seed)).vibe
                val compact = vibeCastJson.encodeToString(Vibe.serializer(), vibe)
                assertEquals(vibe, decodeVibe(vibeApplyJson, compact).getOrThrow(), "$mode seed $seed changes on the compact round trip")
                val full = vibeApplyJson.encodeToString(Vibe.serializer(), vibe)
                assertTrue(compact.length * 3 < full.length * 2, "$mode seed $seed: compact ${compact.length} chars vs full ${full.length}")
            }
        }
    }

    @Test
    fun nullSeedIsRolledAndReported() = runTest {
        val r = cast(args = VibeCastArgs())
        assertTrue(r.ok, r.message)
        assertEquals(r.seed, VibeCaster.cast(CastRequest(CastMode.BED_FIXED, r.seed)).seed)
    }

    @Test
    fun nullModeUsesPreference() = runTest {
        val prefs = FakePrefs(AppPreferences(vibeCastMode = "ALL_ROLLED"))
        val r = cast(prefs, VibeCastArgs(seed = 7L))
        assertEquals("ALL_ROLLED", r.mode)
        assertEquals("BED_FIXED", cast(prefs, VibeCastArgs(mode = "BED_FIXED", seed = 7L)).mode)
        assertEquals("BED_FIXED", cast(FakePrefs(AppPreferences(vibeCastMode = "junk")), VibeCastArgs(seed = 7L)).mode)
    }

    @Test
    fun badPinIsRejected() = runTest {
        val track = cast(args = VibeCastArgs(seed = 1L, pins = listOf(PinArg(track = 9, role = "pad"))))
        assertFalse(track.ok)
        assertTrue("0-7" in track.message, track.message)

        val engine = cast(args = VibeCastArgs(seed = 1L, pins = listOf(PinArg(track = 5, role = "pad", engine = "BD"))))
        assertFalse(engine.ok)
        assertTrue("pick one of" in engine.message, engine.message)

        val nonsense = cast(args = VibeCastArgs(seed = 1L, pins = listOf(PinArg(track = 5, engine = "NOPE"))))
        assertFalse(nonsense.ok)
        assertTrue("NOPE" in nonsense.message, nonsense.message)
    }

    // Every pad the guide's family list names can be pinned, the VA warm organ included.
    @Test
    fun theGuidesPadEnginesCanBePinned() = runTest {
        for (engine in listOf("VA", "ENS", "DX3", "VOWEL_FOF")) {
            val r = cast(args = VibeCastArgs(mode = "ALL_ROLLED", seed = 3L, pins = listOf(PinArg(track = 6, role = "pad", engine = engine))))
            assertTrue(r.ok, "a $engine pad pin was refused: ${r.message}")
        }
    }

    @Test
    fun badModeAndTagAreRejectedWithTheKnownSet() = runTest {
        val mode = cast(args = VibeCastArgs(mode = "chaos"))
        assertFalse(mode.ok)
        assertTrue("BED_FIXED" in mode.message && "ALL_ROLLED" in mode.message, mode.message)
        val tag = cast(args = VibeCastArgs(styleTags = listOf("rock", "polka")))
        assertFalse(tag.ok)
        assertTrue("polka" in tag.message && "ambient" in tag.message, tag.message)
    }

    @Test
    fun descriptionTeachesTheCastAsTheOnlyStart() {
        val d = VibeCastTool(FakePrefs()).tool.descriptor.description
        assertTrue("first" in d.lowercase() && "seed" in d.lowercase() && "shipped" in d.lowercase(), d)
    }

    @Test
    fun oldSavedPrefsWithoutTheFieldStillLoad() {
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<AppPreferences>("""{"selectedAiModel":"flash_25"}""")
        assertEquals("BED_FIXED", old.vibeCastMode)
    }
}
