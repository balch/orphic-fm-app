package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackLabelsTest {

    private fun vibe(edm: OrpheusEngine, space: OrpheusEngine) = Vibe(
        name = "Track Labels Test",
        bpm = 100f,
        rootNote = RootNote.C,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(
            swingAmount = 0f, ghostProbability = 0f,
            noteRangeLow = 36, noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.SPARSE.density,
        ),
        tracks = List(8) {
            TrackVoice(
                engineEdm = edm,
                engineSpace = space,
                role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
            )
        },
    )

    private val dx3 = OrpheusEngine(engineId = OrpheusEngineId.DX3, harmonics = 0.107f)
    private val ens = OrpheusEngine(engineId = OrpheusEngineId.ENS)

    @Test
    fun activeEngineMatchingSpaceSlotWins() {
        val label = trackLabel(vibe(dx3, ens), track = 4, activeEngineId = OrpheusEngineId.ENS.id, energy = 0.9f)
        assertEquals("String Machine", label.instrument)
    }

    @Test
    fun fmPatchNameShown() {
        val label = trackLabel(vibe(dx3, ens), track = 4, activeEngineId = OrpheusEngineId.DX3.id, energy = 0.1f)
        assertEquals("60s organ", label.instrument)
    }

    @Test
    fun unknownIdFallsBackByEnergy() {
        val v = vibe(dx3, ens)
        assertEquals("60s organ", trackLabel(v, track = 4, activeEngineId = -1, energy = 0.6f).instrument)
        assertEquals("String Machine", trackLabel(v, track = 4, activeEngineId = -1, energy = 0.4f).instrument)
    }

    @Test
    fun nameIsPulsarTrackName() {
        val label = trackLabel(vibe(dx3, ens), track = 3, activeEngineId = -1, energy = 0.5f)
        assertEquals(PULSAR_TRACK_NAMES[3], label.name)
    }

    @Test
    fun trackLabelsUsesLiveEngineIdPerTrack() {
        val labels = trackLabels(vibe(dx3, ens), activeEngines = listOf(6, 4), energy = 0.9f)
        assertEquals(8, labels.size)
        assertEquals("String Machine", labels[0].instrument)
        assertEquals("60s organ", labels[1].instrument)
        // Missing live ids fall back by energy (0.9 -> EDM).
        assertEquals("60s organ", labels[7].instrument)
    }
}
