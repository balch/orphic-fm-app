package org.balch.orpheus.djapp.vibeinfo

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VibeInfoMapperTest {

    // ---------------------------------------------------------------------------
    // Test fixture — a minimal Vibe with 8 tracks:
    //
    //   Track 0 (KICK):    BD/BD        Percussive
    //   Track 1 (PERC):    SD/SD        Percussive
    //   Track 2 (HIHAT):   HH/HH        Percussive
    //   Track 3 (BASS):    WSH/STR      Melodic
    //   Track 4 (KEYS):    DX2/GRN      Chordal  ← key assertion: DX2 = "Six-Op FM Bank 2"
    //   Track 5 (PAD):     STR/STR      Melodic
    //   Track 6 (TEXTURE): GRN/GRN      Melodic
    //   Track 7 (FX):      MOD/STR      Melodic
    //
    // Arrangement: ["intro", "verse", "chorus"]
    // RootNote.G (noteIndex=7), ScaleType.MAJOR (scaleIndex=1)
    // ---------------------------------------------------------------------------

    private fun eng(id: OrpheusEngineId) = OrpheusEngine(engineId = id)

    private val sections = listOf(
        Section(name = "intro",  barsMin = 2, barsMax = 4),
        Section(name = "verse",  barsMin = 4, barsMax = 8),
        Section(name = "chorus", barsMin = 4, barsMax = 6),
    )

    private val testVibe = Vibe(
        name = "Test Vibe",
        bpm = 120f,
        rootNote = RootNote.G,        // noteIndex=7 → "G"
        scaleType = ScaleType.MAJOR,  // scaleIndex=1 → "Major"
        genre = GenreProfile(
            swingAmount = 0.0f,
            ghostProbability = 0.0f,
            noteRangeLow = 36,
            noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.FOUR_ON_FLOOR.density,
        ),
        arrangement = Arrangement(
            introIndex = 0,
            outroIndex = 2,
            sections = sections,
        ),
        effects = VibeEffects(
            reverbSize = 0.60f,     // → reverbPct = 60
            delayFeedback = 0.35f,  // → delayPct  = 35
        ),
        tracks = listOf(
            TrackVoice(engineEdm = eng(OrpheusEngineId.BD),  engineSpace = eng(OrpheusEngineId.BD),  role = TrackRole.Percussive),
            TrackVoice(engineEdm = eng(OrpheusEngineId.SD),  engineSpace = eng(OrpheusEngineId.SD),  role = TrackRole.Percussive),
            TrackVoice(engineEdm = eng(OrpheusEngineId.HH),  engineSpace = eng(OrpheusEngineId.HH),  role = TrackRole.Percussive),
            TrackVoice(engineEdm = eng(OrpheusEngineId.WSH), engineSpace = eng(OrpheusEngineId.STR), role = TrackRole.Melodic()),
            TrackVoice(engineEdm = eng(OrpheusEngineId.DX2), engineSpace = eng(OrpheusEngineId.GRN), role = TrackRole.Chordal()),
            TrackVoice(engineEdm = eng(OrpheusEngineId.STR), engineSpace = eng(OrpheusEngineId.STR), role = TrackRole.Melodic()),
            TrackVoice(engineEdm = eng(OrpheusEngineId.GRN), engineSpace = eng(OrpheusEngineId.GRN), role = TrackRole.Melodic()),
            TrackVoice(engineEdm = eng(OrpheusEngineId.MOD), engineSpace = eng(OrpheusEngineId.STR), role = TrackRole.Melodic()),
        ),
    )

    private fun defaultArrangement(sectionIndex: Int = 0) = PulsarArrangementState(
        sectionIndex = sectionIndex,
        barsElapsed = 1, barsTotal = 4,
        soloActive = false, soloTrack = -1, soloMode = 0,
    )

    // ─── Assertion 1: engine-current selection by energy ───────────────────────

    @Test
    fun `track 4 at high energy uses engineEdm DX2 shown as its harmonics-selected patch`() {
        // energy >= 0.5 → pick engineEdm (DX2). Default harmonics 0.5 → patch idx 16 = "Xylophone"
        // (SixOp engines display the specific patch, not the generic "Six-Op FM Bank 2").
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(),
            energy = 0.8f,
        )
        assertEquals("Xylophone", result.tracks[4].instrument)
    }

    // ─── Assertion 1c: SixOp patch-name lookup ────────────────────────────────

    @Test
    fun `FmPatchNames maps harmonics to the specific bank patch`() {
        // The engine floors (harmonics * 32.64), it does not round — see
        // FmPatchNames' KDoc and PinPatchCalculatorTest.
        assertEquals("Solid bass", FmPatchNames.patchNameFor(OrpheusEngineId.DX, 0.000f))   // bank0 idx0
        assertEquals("Clav 3",     FmPatchNames.patchNameFor(OrpheusEngineId.DX2, 0.490f))  // bank1 idx15
        assertEquals("Marimba",    FmPatchNames.patchNameFor(OrpheusEngineId.DX2, 0.521f))  // bank1 idx17
        assertEquals("Br trumpet", FmPatchNames.patchNameFor(OrpheusEngineId.DX3, 0.950f))  // bank2 idx31
        assertEquals("Syn orch",   FmPatchNames.patchNameFor(OrpheusEngineId.DX3, 0.888f))  // bank2 idx28
        assertEquals("Xylophone",  FmPatchNames.patchNameFor(OrpheusEngineId.DX2, 0.5f))    // floor(16.32)=16
    }

    /**
     * Values taken verbatim from shipped vibes whose own comments name the patch
     * one index too high — the pre-fix authoring formula aimed at a bucket's lower
     * edge. These pin the patch that actually sounds.
     */
    @Test
    fun `FmPatchNames reports the patch that actually loads for shipped vibe anchors`() {
        // DogHouseVibe track 4 EDM — comment says idx 18 "Spiral"
        assertEquals("Insert 1", FmPatchNames.patchNameFor(OrpheusEngineId.DX, 0.551f))
        // SwampSwaggerVibe — comment says idx 18 "Vibe 1"
        assertEquals("Marimba", FmPatchNames.patchNameFor(OrpheusEngineId.DX2, 0.551f))
        // ArmyStompVibe lead EDM — comment says idx 5 "Clav E pno"
        assertEquals("Mark III", FmPatchNames.patchNameFor(OrpheusEngineId.DX2, 0.153f))
    }

    /**
     * Anchors that sit inside the 0.005 hysteresis band just *above* a bucket edge.
     * A plain `floor(harmonics * 32.64)` names these one index too high; the real
     * engine resolves them downward on a fresh load. Guards the `- 0.005f` term.
     */
    @Test
    fun `FmPatchNames resolves edge anchors the way a fresh engine load does`() {
        // RustedCoast / FireSky / BluesBurn organ space slot — 0.092 * 32.64 = 3.003
        assertEquals("E organ 3", FmPatchNames.patchNameFor(OrpheusEngineId.DX3, 0.092f))
        // VelvetLeashVibe — 0.4596 * 32.64 = 15.001
        assertEquals("Harpsich", FmPatchNames.patchNameFor(OrpheusEngineId.DX2, 0.4596f))
        // VoltageStrutVibe — 0.4902 * 32.64 = 16.000
        assertEquals("Clav 3", FmPatchNames.patchNameFor(OrpheusEngineId.DX2, 0.4902f))
    }

    /**
     * The centred formula the vibe-creator skill now documents must resolve to the
     * patch it targets for all 32 indices in every bank.
     */
    @Test
    fun `FmPatchNames round-trips the documented centre formula for all 32 patches`() {
        val banks = listOf(OrpheusEngineId.DX, OrpheusEngineId.DX2, OrpheusEngineId.DX3)
        for (engineId in banks) {
            for (patch in 0..31) {
                val harmonics = (patch + 0.5f) / (32f * 1.02f)
                assertEquals(
                    FmPatchNames.patchNameAt(engineId, patch),
                    FmPatchNames.patchNameFor(engineId, harmonics),
                    "centre($patch) = $harmonics should name patch $patch on $engineId",
                )
            }
        }
    }

    @Test
    fun `FmPatchNames returns null for non-SixOp engines`() {
        assertNull(FmPatchNames.patchNameFor(OrpheusEngineId.GRN, 0.5f))
        assertNull(FmPatchNames.patchNameFor(OrpheusEngineId.BD, 0.5f))
        assertNull(FmPatchNames.patchNameFor(OrpheusEngineId.STR, 0.5f))
    }

    @Test
    fun `track 4 at low energy uses engineSpace - GRN = Grain`() {
        // energy < 0.5 → pick engineSpace → GRN
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(),
            energy = 0.3f,
        )
        assertEquals("Grain", result.tracks[4].instrument)
    }

    @Test
    fun `live active engine id overrides the energy guess`() {
        // High energy would guess engineEdm (DX2), but the DSP reports GRN (track 4's
        // SPACE engine) as live-active — the live id must win.
        val activeEngines = IntArray(8) { -1 }.also { it[4] = OrpheusEngineId.GRN.id }
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(activeEngines = activeEngines),
            energy = 0.9f,
        )
        assertEquals("Grain", result.tracks[4].instrument)
    }

    // ─── Assertion 2: section isNowPlaying / isPast ─────────────────────────────

    @Test
    fun `section at sectionIndex is now-playing and earlier sections are past`() {
        // sectionIndex=2 → chorus=nowPlaying; intro+verse=past
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(sectionIndex = 2),
            viz = PulsarVizData(),
            energy = 0.5f,
        )

        assertEquals(3, result.sections.size)

        assertFalse(result.sections[0].isNowPlaying, "intro: not now-playing")
        assertTrue(result.sections[0].isPast,        "intro: is past")

        assertFalse(result.sections[1].isNowPlaying, "verse: not now-playing")
        assertTrue(result.sections[1].isPast,        "verse: is past")

        assertTrue(result.sections[2].isNowPlaying,  "chorus: is now-playing")
        assertFalse(result.sections[2].isPast,       "chorus: not past (it is current)")
    }

    @Test
    fun `first section is now-playing with no past sections`() {
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(sectionIndex = 0),
            viz = PulsarVizData(),
            energy = 0.5f,
        )

        assertTrue(result.sections[0].isNowPlaying)
        assertFalse(result.sections[0].isPast)
        assertFalse(result.sections[1].isNowPlaying)
        assertFalse(result.sections[1].isPast)
    }

    @Test
    fun `section names are carried through`() {
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(sectionIndex = 1),
            viz = PulsarVizData(),
            energy = 0.5f,
        )
        assertEquals("intro",  result.sections[0].name)
        assertEquals("verse",  result.sections[1].name)
        assertEquals("chorus", result.sections[2].name)
    }

    // ─── Assertion 3: isPlaying from trackLevels vs threshold ──────────────────

    @Test
    fun `track above threshold is playing and track below threshold is not`() {
        val trackLevels = FloatArray(8) { 0.0f }
        trackLevels[0] = 0.05f  // above default threshold 0.02f
        trackLevels[1] = 0.01f  // below threshold

        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(trackLevels = trackLevels),
            energy = 0.5f,
        )

        assertTrue(result.tracks[0].isPlaying,  "0.05 > 0.02 → isPlaying")
        assertFalse(result.tracks[1].isPlaying, "0.01 < 0.02 → not isPlaying")
    }

    @Test
    fun `custom trackLevelThreshold is respected`() {
        val trackLevels = FloatArray(8) { 0.0f }
        trackLevels[3] = 0.10f  // above custom threshold 0.05f
        // track 0 level stays at 0.0f < 0.05f

        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(trackLevels = trackLevels),
            energy = 0.5f,
            trackLevelThreshold = 0.05f,
        )

        assertTrue(result.tracks[3].isPlaying,  "0.10 > custom 0.05 → isPlaying")
        assertFalse(result.tracks[0].isPlaying, "0.0 < custom 0.05 → not isPlaying")
    }

    @Test
    fun `short trackLevels array does not throw and all tracks report isPlaying false`() {
        // Regression: mapVibeInfo must be total — trackLevels shorter than track count
        // (e.g. default PulsarVizData before live viz populates) must not throw AIOOBE.
        listOf(FloatArray(0), FloatArray(2)).forEach { shortLevels ->
            val result = mapVibeInfo(
                vibe = testVibe,
                arrangement = defaultArrangement(),
                viz = PulsarVizData(trackLevels = shortLevels),
                energy = 0.5f,
            )
            result.tracks.forEachIndexed { i, track ->
                assertFalse(track.isPlaying, "Track $i should not be playing when trackLevels is shorter than tracks")
            }
        }
    }

    // ─── Other scalar fields ────────────────────────────────────────────────────

    @Test
    fun `name bpm keyName scaleName reverbPct delayPct are mapped correctly`() {
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(),
            energy = 0.5f,
        )

        assertEquals("Test Vibe", result.name)
        assertEquals(120, result.bpm)
        assertEquals("G", result.keyName)       // RootNote.G.noteIndex=7 → NoteNames[7]
        assertEquals("Major", result.scaleName) // ScaleType.MAJOR.scaleIndex=1 → ScaleNames[1]
        assertEquals(60, result.reverbPct)      // (0.60f * 100).toInt()
        assertEquals(35, result.delayPct)       // (0.35f * 100).toInt()
    }

    @Test
    fun `vibe with no arrangement produces empty sections list`() {
        val vibeNoArrangement = testVibe.copy(arrangement = null)

        val result = mapVibeInfo(
            vibe = vibeNoArrangement,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(),
            energy = 0.5f,
        )

        assertTrue(result.sections.isEmpty())
    }

    // ─── Track role display strings ─────────────────────────────────────────────

    @Test
    fun `TrackRole maps to correct display string`() {
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(),
            energy = 0.5f,
        )

        assertEquals("Drums/Perc",   result.tracks[0].role)  // Percussive
        assertEquals("Lead/Melodic", result.tracks[3].role)  // Melodic
        assertEquals("Chords",       result.tracks[4].role)  // Chordal
    }

    // ─── Track labels from PULSAR_TRACK_NAMES ───────────────────────────────────

    @Test
    fun `track labels match PULSAR_TRACK_NAMES`() {
        val result = mapVibeInfo(
            vibe = testVibe,
            arrangement = defaultArrangement(),
            viz = PulsarVizData(),
            energy = 0.5f,
        )

        val expected = listOf("KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXTURE", "FX")
        result.tracks.forEachIndexed { i, track ->
            assertEquals(expected[i], track.label, "Track $i label")
        }
    }
}
