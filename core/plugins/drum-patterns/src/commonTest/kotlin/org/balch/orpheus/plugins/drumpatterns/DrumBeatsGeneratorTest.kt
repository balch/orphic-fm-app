package org.balch.orpheus.plugins.drumpatterns

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lives here rather than in an app module: this module is GPL-3.0 and `features:beats` reaches
 * it through `implementation`, so nothing downstream can see [DrumBeatsGenerator] to test it.
 * A module testing its own code is the only placement that does not open a copyleft edge.
 *
 * Both cases assert only that triggers arrive. The exact pattern comes from the Grids LUT, and
 * pinning it here would restate the ROM rather than test the generator driving it.
 */
class DrumBeatsGeneratorTest {

    /** 6 ticks per 16th note, per the generator's own resolution. */
    private val ticksPerStep = 6

    @Test
    fun `euclidean mode triggers within a full pattern length`() {
        val triggers = mutableListOf<Pair<Int, Float>>()
        val generator = DrumBeatsGenerator { type, accent -> triggers.add(type to accent) }

        generator.outputMode = DrumBeatsGenerator.OutputMode.EUCLIDEAN
        generator.setEuclideanLength(0, 4)
        generator.setDensity(0, 0.5f)

        triggers.clear()
        repeat(ticksPerStep * 4) { generator.tick() }

        assertTrue(triggers.isNotEmpty(), "Euclidean mode should trigger across a length-4 cycle")
    }

    @Test
    fun `grids mode triggers at the origin of the pattern map`() {
        val triggers = mutableListOf<Pair<Int, Float>>()
        val generator = DrumBeatsGenerator { type, accent -> triggers.add(type to accent) }

        generator.outputMode = DrumBeatsGenerator.OutputMode.DRUMS
        generator.setX(0f)
        generator.setY(0f)
        generator.setDensity(0, 0.9f)

        triggers.clear()
        repeat(ticksPerStep * DrumBeatsGenerator.NUM_STEPS) { generator.tick() }

        assertTrue(triggers.isNotEmpty(), "Drums mode should trigger over a full 32-step cycle")
    }
}
