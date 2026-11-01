package org.balch.orpheus.djapp.ai.licklab

import kotlin.test.Test
import kotlin.test.assertEquals
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.MelodicOverride
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.SoloBehavior
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe

class LickPitchTest {
    private val lead = SlotRef(LickSlot.Lead)

    private fun track(role: TrackRole = TrackRole.Percussive, solo: SoloBehavior? = null) = TrackVoice(
        engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
        engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
        role = role,
        soloBehavior = solo,
    )

    private fun vibe(
        root: RootNote = RootNote.B,
        scale: ScaleType = ScaleType.MINOR,
        lickOctave: Int = 5,
        bassOctave: Int = 2,
        low: Int = 36,
        high: Int = 72,
        tracks: List<TrackVoice> = List(8) { track() },
    ) = Vibe(
        name = "t",
        tracks = tracks,
        lickOctave = lickOctave,
        bassLineOctave = bassOctave,
        bpm = 120f,
        rootNote = root,
        scaleType = scale,
        genre = GenreProfile(0f, 0f, low, high, RhythmPattern.SPARSE.density),
    )

    @Test
    fun `explicit octave and degree wrap`() {
        val v = vibe()
        assertEquals(71, LickPitch.baseMidi(v, lead))
        assertEquals(71, LickPitch.degreeToMidi(0, v, lead))
        assertEquals(69, LickPitch.degreeToMidi(-1, v, lead))
        assertEquals(59, LickPitch.degreeToMidi(-7, v, lead))
        assertEquals(83, LickPitch.degreeToMidi(7, v, lead))
        assertEquals(35, LickPitch.baseMidi(v, SlotRef(LickSlot.Bass)))
        assertEquals(71, LickPitch.baseMidi(v, SlotRef(LickSlot.Pool, 2)))
    }

    @Test
    fun `solo slot adds the override octave shift`() {
        val solo = SoloBehavior(melodicOverride = MelodicOverride(octaveShift = -1))
        val v = vibe(tracks = List(8) { if (it == 3) track(solo = solo) else track() })
        assertEquals(59, LickPitch.baseMidi(v, SlotRef(LickSlot.Solo, 3)))
        assertEquals(71, LickPitch.baseMidi(v, SlotRef(LickSlot.Solo, 4)))
    }

    // Parity with lick_octave_base in pulsar_pattern_gen.h.
    @Test
    fun `auto octave mirrors the engine fallback`() {
        // mid 54 - root 11 = 43 -> 36
        assertEquals(11 + 36, LickPitch.baseMidi(vibe(lickOctave = -1), lead))
        // mid 10 clamps up to 24; root 0 -> 24
        assertEquals(24, LickPitch.baseMidi(vibe(root = RootNote.C, lickOctave = -1, low = 0, high = 20), lead))
        // mid 110 clamps down to 84; 84 - 7 = 77 -> 72
        assertEquals(7 + 72, LickPitch.baseMidi(vibe(root = RootNote.G, lickOctave = -1, low = 100, high = 120), lead))
        // bass uses its own octave field
        assertEquals(11 + 36, LickPitch.baseMidi(vibe(bassOctave = -1), SlotRef(LickSlot.Bass)))
    }

    @Test
    fun `midi to degree is exact in scale and snaps off scale`() {
        val v = vibe()
        assertEquals(DegreeOrChromatic.Degree(0), LickPitch.midiToDegree(71, v, lead))
        assertEquals(DegreeOrChromatic.Degree(-1), LickPitch.midiToDegree(69, v, lead))
        assertEquals(DegreeOrChromatic.Degree(9), LickPitch.midiToDegree(LickPitch.degreeToMidi(9, v, lead), v, lead))
        // 72 is a semitone above degree 0 and below degree 1: tie goes to the lower index
        assertEquals(DegreeOrChromatic.Chromatic(0, 72), LickPitch.midiToDegree(72, v, lead))
    }

    @Test
    fun `note names`() {
        assertEquals("B4", LickPitch.noteName(71))
        assertEquals("C4", LickPitch.noteName(60))
    }

    @Test
    fun `default slot follows the track lick source`() {
        val tracks = listOf(
            track(TrackRole.Melodic(lickSource = LickSource.BASS)),
            track(TrackRole.Melodic()),
            track(),
        )
        val v = vibe(tracks = tracks + List(5) { track() })
        assertEquals(SlotRef(LickSlot.Bass), LickPitch.defaultSlotFor(v, 0))
        assertEquals(lead, LickPitch.defaultSlotFor(v, 1))
        assertEquals(lead, LickPitch.defaultSlotFor(v, 2))
    }
}
