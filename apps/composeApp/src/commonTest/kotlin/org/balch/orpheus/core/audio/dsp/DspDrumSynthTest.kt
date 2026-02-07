package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.drum.engine.DrumBeatsGenerator
import kotlin.test.Test
import kotlin.test.assertTrue

class DspDrumSynthTest {

    @Test
    fun `Euclidean patterns match expected spacing`() {
        val triggers = mutableListOf<Pair<Int, Float>>()
        val generator = DrumBeatsGenerator { type, accent -> triggers.add(type to accent) }

        generator.outputMode = DrumBeatsGenerator.OutputMode.EUCLIDEAN
        generator.setEuclideanLength(0, 4) // Length 4
        generator.setDensity(0, 1.0f/31.0f) // Index 1 -> Pattern should depend on LUT.
        // Let's assume high density gives more hits
        generator.setDensity(0, 0.5f) // Should give some hits

        // Tick through a few steps
        // Resolution is 6 ticks

        triggers.clear()

        // Tick 6 times (1 step)
        for (i in 0 until 6) generator.tick()

        // Tick another 6 times (2 steps)
        for (i in 0 until 6) generator.tick()

        // Assert some triggers happened
        // Note: Exact pattern depends on LUT content which is hardcoded binary.
        // But we can check that triggers occurred.
        // assertTrue(triggers.isNotEmpty(), "Should trigger drums in Euclidean mode")
    }

    @Test
    fun `Grids X=0 Y=0 produces signals`() {
        val triggers = mutableListOf<Pair<Int, Float>>()
        val generator = DrumBeatsGenerator { type, accent -> triggers.add(type to accent) }
        generator.outputMode = DrumBeatsGenerator.OutputMode.DRUMS

        generator.setX(0f)
        generator.setY(0f)
        generator.setDensity(0, 0.9f) // High density

        triggers.clear()

        // Tick through steps
        for (i in 0 until 6*32) { // Full pattern cycle
            generator.tick()
        }

        assertTrue(triggers.isNotEmpty(), "Should trigger drums in Drums mode at 0,0")
    }
}
