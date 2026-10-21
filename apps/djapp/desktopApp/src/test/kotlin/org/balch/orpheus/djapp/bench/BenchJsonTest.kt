package org.balch.orpheus.djapp.bench

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.VibeLayer
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchJsonTest {
    private val vibe = Vibe(
        name = "bench",
        tracks = List(8) { TrackVoice(OrpheusEngine(OrpheusEngineId.VA), OrpheusEngine(OrpheusEngineId.VA)) },
        bpm = 120f,
        rootNote = RootNote.A,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(
            swingAmount = 0f,
            ghostProbability = 0f,
            noteRangeLow = 36,
            noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.SPARSE.density,
        ),
        energy = 0.3f,
    )

    private fun view(layers: BenchLayers) = BenchView(
        vibe = vibe, section = -1, layers = layers, scope = Scope.VIBE, muted = emptySet(), soloed = emptySet(),
        loopSection = null, playing = false, rows = emptyList(), overviewStatus = "none", stale = false,
        source = null, knobs = mapOf(Macro.ENERGY to 0.8f),
    )

    private fun energy(layers: BenchLayers) =
        BenchJson.state(view(layers))["macros"]!!.jsonObject["ENERGY"]!!.jsonObject

    @Test
    fun untouchedKnobShowsTheAppValueNotTheFile() {
        val e = energy(BenchLayers())
        assertEquals(0.3f, e["file"]!!.jsonPrimitive.float)
        assertEquals(0.8f, e["live"]!!.jsonPrimitive.float)
        assertEquals(0.8f, e["knob"]!!.jsonPrimitive.float)
        assertFalse(e["knobMoved"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun movedKnobShowsTheBenchValue() {
        val e = energy(BenchLayers(vibe = VibeLayer(macros = mapOf(Macro.ENERGY to 0.6f))))
        assertEquals(0.6f, e["knob"]!!.jsonPrimitive.float)
        assertTrue(e["knobMoved"]!!.jsonPrimitive.boolean)
    }
}
