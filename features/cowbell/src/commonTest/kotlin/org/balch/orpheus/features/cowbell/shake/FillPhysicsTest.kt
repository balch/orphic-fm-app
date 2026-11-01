package org.balch.orpheus.features.cowbell.shake

import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.strikeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FillPhysicsTest {

    private val step = 1.0 / 120.0

    private fun kindOf(packed: Float) = (packed.toInt() shr 14) and 15
    private fun vel7Of(packed: Float) = packed.toInt() and 127

    @Test
    fun marbleRollsUnderTilt() {
        val physics = FillPhysics(CowbellFill.MARBLE, Random(7))
        val strikes = FillStrikes()
        var bodyHits = 0
        val drain: (Float) -> Unit = { if (kindOf(it) == 0) bodyHits++ }
        repeat(120) {
            physics.advance(step, 1f, 0f, 0f, 0f, strikes)
            strikes.drain(drain)
        }
        repeat(120) {
            physics.advance(step, -1f, 0f, 0f, 0f, strikes)
            strikes.drain(drain)
        }
        assertTrue(physics.particles.single().x < 0f, "marble x=${physics.particles.single().x}")
        assertTrue(bodyHits >= 1, "no kind 0 wall strike")
    }

    @Test
    fun restingFillFallsSilent() {
        val physics = FillPhysics(CowbellFill.SEEDS, Random(7))
        val strikes = FillStrikes()
        val jitter = Random(11)
        repeat(120) {
            physics.advance(step, 0f, 0f, 0f, 0f, strikes)
            strikes.drain { }
        }
        var late = 0
        repeat(5 * 120) { i ->
            val gx = (jitter.nextFloat() - 0.5f) * 0.02f
            val gz = (jitter.nextFloat() - 0.5f) * 0.02f
            physics.advance(step, gx, gz, 0f, 0f, strikes)
            strikes.drain { if (i >= 120) late++ }
        }
        assertEquals(0, late)
    }

    @Test
    fun settledUnderTiltFallsSilent() {
        val physics = FillPhysics(CowbellFill.SEEDS, Random(7))
        val strikes = FillStrikes()
        val jitter = Random(11)
        repeat(6 * 120) {
            physics.advance(step, 0.8f, 0.5f, 0f, 0f, strikes)
            strikes.drain { }
        }
        var late = 0
        repeat(4 * 120) {
            val gx = 0.8f + (jitter.nextFloat() - 0.5f) * 0.02f
            val gz = 0.5f + (jitter.nextFloat() - 0.5f) * 0.02f
            physics.advance(step, gx, gz, 0f, 0f, strikes)
            strikes.drain { late++ }
        }
        assertEquals(0, late)
    }

    private fun shakenStrikeCount(fps: Int): Int {
        val physics = FillPhysics(CowbellFill.SEEDS, Random(7))
        val strikes = FillStrikes()
        val dt = 1.0 / fps
        val frames = 2 * fps
        for (i in 0 until frames) {
            val t = i * dt
            // A 3 Hz sway: kick is the displacement this frame, so it scales with the frame time.
            val rate = 400.0 * sin(2.0 * PI * 3.0 * t)
            physics.advance(dt, 0f, 0f, (rate * dt).toFloat(), 0f, strikes)
            strikes.drain { }
        }
        return strikes.added
    }

    @Test
    fun stepIsFrameRateIndependent() {
        val counts = listOf(30, 60, 144).map { shakenStrikeCount(it) }
        val low = counts.min()
        val high = counts.max()
        assertTrue(low > 20, "too few strikes to compare: $counts")
        assertTrue(high <= low * 1.15, "counts differ by more than 15%: $counts")
    }

    @Test
    fun strikeCapKeepsLoudest() {
        val strikes = FillStrikes()
        val velocities = (0 until 12).map { 0.1f + it * (0.9f / 11f) }
        // A scrambled order, so the cap has to evict, not just stop accepting.
        val order = listOf(3, 9, 0, 11, 5, 1, 7, 10, 2, 8, 4, 6)
        order.forEach { strikes.add(velocities[it], 2, 40) }
        val drained = mutableListOf<Float>()
        strikes.drain { drained += it }
        assertEquals(5, drained.size)
        val expected = velocities.takeLast(5).map { vel7Of(cowbellPack(it)) }.toSet()
        assertEquals(expected, drained.map { vel7Of(it) }.toSet())
        assertTrue(drained.all { kindOf(it) == 2 })
        // Drained means empty.
        var again = 0
        strikes.drain { again++ }
        assertEquals(0, again)
    }

    private fun cowbellPack(velocity: Float) =
        org.balch.orpheus.plugins.cowbell.cowbellStrikeValue(velocity, 2, 40)

    @Test
    fun bodyHitsRateLimited() {
        val physics = FillPhysics(CowbellFill.BEADS, Random(7))
        val strikes = FillStrikes()
        val bodySteps = mutableListOf<Int>()
        for (i in 0 until 120) {
            val kick = if (i % 12 < 6) 60f else -60f
            physics.advance(step, 0f, 0f, kick, 0f, strikes)
            strikes.drain { if (kindOf(it) == 0) bodySteps += i }
        }
        assertTrue(bodySteps.isNotEmpty(), "no body hits at all")
        assertTrue(bodySteps.size <= 13, "body hits=${bodySteps.size}")
        bodySteps.zipWithNext().forEach { (a, b) ->
            assertTrue((b - a) * step >= 0.08, "body hits ${a} and ${b} too close")
        }
    }

    @Test
    fun plungerLaunches() {
        val physics = FillPhysics(CowbellFill.BEADS, Random(7))
        val strikes = FillStrikes()
        repeat(120) {
            physics.advance(step, 0f, 0f, 0f, 0f, strikes)
            strikes.drain { }
        }
        // Held for 2 s: the first second is the pile slamming into the wall, then it lies still.
        var lastHeldWindow = 0
        repeat(240) { i ->
            val before = strikes.added
            physics.plunge(-1f)
            physics.advance(step, 0f, 0f, 0f, 0f, strikes)
            strikes.drain { }
            if (i >= 240 - 36) lastHeldWindow += strikes.added - before
        }
        physics.release(-1f)
        physics.advance(step, 0f, 0f, 0f, 0f, strikes)
        val meanVx = physics.particles.map { it.vx }.average()
        assertTrue(meanVx > 0.0, "mean vx=$meanVx")
        val afterStart = strikes.added
        repeat(36) {
            physics.advance(step, 0f, 0f, 0f, 0f, strikes)
            strikes.drain { }
        }
        val after = strikes.added - afterStart
        assertTrue(after > lastHeldWindow, "held=$lastHeldWindow after=$after")
        assertTrue(after >= 5, "release should rattle the fill: $after")
    }

    @Test
    fun nanInputsIgnored() {
        val physics = FillPhysics(CowbellFill.BEADS, Random(7))
        val strikes = FillStrikes()
        repeat(30) {
            physics.advance(step, Float.NaN, Float.NaN, Float.NaN, Float.NaN, strikes)
            physics.advance(step, Float.POSITIVE_INFINITY, 0f, Float.NEGATIVE_INFINITY, 0f, strikes)
            strikes.drain { }
        }
        physics.advance(Double.NaN, 1f, 1f, 1f, 1f, strikes)
        physics.plunge(Float.NaN)
        physics.release(Float.NaN)
        physics.advance(step, 0f, 0f, 0f, 0f, strikes)
        physics.particles.forEach {
            assertTrue(it.x.isFinite() && it.z.isFinite() && it.vx.isFinite() && it.vz.isFinite())
            assertTrue(abs(it.x) < 1f && abs(it.z) < 1f, "left the bell: ${it.x} ${it.z}")
        }
    }

    @Test
    fun fillKindsAndCounts() {
        assertEquals(1, FillPhysics(CowbellFill.MARBLE, Random(1)).particles.size)
        assertEquals(10, FillPhysics(CowbellFill.BEADS, Random(1)).particles.size)
        assertEquals(45, FillPhysics(CowbellFill.SEEDS, Random(1)).particles.size)
        assertEquals(6, FillPhysics(CowbellFill.JINGLES, Random(1)).particles.size)
        assertEquals(listOf(1, 2, 3, 4), CowbellFill.entries.map { it.strikeKind })
    }
}
