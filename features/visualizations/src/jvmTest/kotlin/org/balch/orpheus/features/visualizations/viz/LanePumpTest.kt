package org.balch.orpheus.features.visualizations.viz

import org.balch.orpheus.core.plugin.viz.PulsarVizData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanePumpTest {
    /** Track 0 on [step] of a 16-step pattern with a gate every 4 steps at [velocity]. */
    private fun viz(step: Int, velocity: Float = 1f, gated: Boolean = true) = PulsarVizData(
        stepGates = Array(8) { t -> BooleanArray(32) { s -> gated && t == 0 && s % 4 == 0 } },
        stepVelocities = Array(8) { FloatArray(32) { velocity } },
        playheads = IntArray(8) { t -> if (t == 0) step else -1 },
    )

    /** Plays [steps] steps of track 0 at [stepSeconds] each, updating at 60 fps; returns the end time. */
    private fun play(pump: LanePump, steps: Int, stepSeconds: Double, velocity: Float = 1f, from: Double = 0.0): Double {
        var now = from
        val end = from + steps * stepSeconds
        while (now < end) {
            pump.update(now, viz(((now - from) / stepSeconds).toInt() % 16, velocity))
            now += 1.0 / 60
        }
        return now
    }

    @Test fun `a gated step pumps its lane toward the step's velocity`() {
        val pump = LanePump(8)
        play(pump, steps = 1, stepSeconds = 0.125, velocity = 0.8f)
        assertTrue(pump.level[0] > 0.5f, "pump ${pump.level[0]} after a 0.8 hit")
        assertTrue(pump.level[0] <= 0.8f)
        assertEquals(0f, pump.level[1], "an idle track does not pump")
    }

    @Test fun `ungated steps do not pump`() {
        val pump = LanePump(8)
        var now = 0.0
        repeat(30) { pump.update(now, viz(it % 16, gated = false)); now += 1.0 / 60 }
        assertEquals(0f, pump.level[0])
    }

    @Test fun `the tempo comes from the playhead`() {
        val pump = LanePump(8)
        play(pump, steps = 32, stepSeconds = 0.1)
        assertEquals(0.1f, pump.stepSeconds, 0.01f)
    }

    @Test fun `a slow song relaxes slower than a fast one`() {
        // Same groove, a step of 0.08 s against 0.2 s; then half a second with no new steps.
        fun afterRest(stepSeconds: Double): Float {
            val pump = LanePump(8)
            var now = play(pump, steps = 16, stepSeconds = stepSeconds)
            val held = viz(-1)
            repeat(30) { pump.update(now, held); now += 1.0 / 60 }
            return pump.level[0]
        }
        val fast = afterRest(0.08)
        val slow = afterRest(0.2)
        assertTrue(slow > fast * 2, "slow $slow should outlast fast $fast")
    }
}
