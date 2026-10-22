package org.balch.orpheus.features.pulsar.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe

class MacroViewTest {
    @Test
    fun sectionMultiplierScalesTheKnob() {
        val vibe = Vibe(
            name = "Macro View Test",
            bpm = 100f,
            rootNote = RootNote.C,
            scaleType = ScaleType.MINOR,
            genre = GenreProfile(
                swingAmount = 0f, ghostProbability = 0f, noteRangeLow = 36, noteRangeHigh = 72,
                rhythmDensity = RhythmPattern.SPARSE.density,
            ),
            tracks = List(8) {
                TrackVoice(
                    engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
                    engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
                )
            },
            arrangement = Arrangement(
                sections = listOf(Section("a"), Section("b", macroOverrides = MacroOverrides(energy = 0.8f))),
            ),
        )
        val v = BenchLayers().macroView(vibe, 1, Macro.ENERGY, 0.5f)
        assertEquals(0.8f, v.mult)
        assertEquals(0.4f, v.heard, 1e-6f)
        assertFalse(v.knobMoved)
    }
}
