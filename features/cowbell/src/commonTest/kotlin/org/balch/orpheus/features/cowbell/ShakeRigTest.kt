package org.balch.orpheus.features.cowbell

import org.balch.orpheus.features.cowbell.motion.JoltWindow
import org.balch.orpheus.features.cowbell.motion.MotionSample
import org.balch.orpheus.features.cowbell.motion.MotionSource
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The frame loop's tilt, kick and physics hand-offs, with the clock driven by hand. */
class ShakeRigTest {

    private class Phone(override val available: Boolean = true) : MotionSource {
        var gx = 0f
        var gy = 0f
        var gz = 1f
        var kickX = 0f
        var kickY = 0f
        override val jolts = JoltWindow(capacity = 1)
        override fun start() {}
        override fun stop() {}
        override fun read(into: MotionSample) {
            into.gravityX = gx
            into.gravityY = gy
            into.gravityZ = gz
            into.kickX = kickX
            into.kickY = kickY
            kickX = 0f
            kickY = 0f
        }

        /** Held at [degrees] with the top edge rising and the right edge dropping by [rightDown]. */
        fun hold(topUp: Float, rightDown: Float = 0f) {
            val p = topUp * PI.toFloat() / 180f
            val r = rightDown * PI.toFloat() / 180f
            // Readings are the reaction to gravity: they point up, so a dropped right edge reads negative x.
            gx = -sin(r)
            gy = -sin(p)
            gz = cos(p) * cos(r)
        }
    }

    private class Harness(val phone: Phone, mode: CowbellMode = CowbellMode.TAP, val fill: CowbellFill = CowbellFill.MARBLE) {
        val tilts = mutableListOf<Float>()
        val strikes = mutableListOf<Float>()
        val actions = CowbellPanelActions.EMPTY.copy(onTilt = { tilts += it }, onFillStrike = { strikes += it })
        val rig = ShakeRig(phone, Random(3))
        var mode = mode
        var morphing = false
        var motionOn = true
        var seconds = 0.0

        fun frames(count: Int, dt: Double = 1.0 / 60.0) {
            repeat(count) {
                seconds += dt
                rig.frame(seconds, mode, fill, morphing, actions, motionOn = motionOn)
            }
        }
    }

    @Test
    fun droppedRightEdgeTiltsTheFillRight() {
        val phone = Phone()
        val h = Harness(phone)
        h.frames(30)
        phone.hold(topUp = 0f, rightDown = 20f)
        h.frames(2)
        assertTrue(h.rig.tiltX > 0.5f, "tiltX ${h.rig.tiltX}")
        assertEquals(0f, h.rig.tiltY, 1e-3f)
    }

    @Test
    fun raisedTopEdgeTiltsTheFillDown() {
        val phone = Phone()
        val h = Harness(phone)
        h.frames(30)
        phone.hold(topUp = 20f)
        h.frames(2)
        assertTrue(h.rig.tiltY > 0.5f, "tiltY ${h.rig.tiltY}")
        assertTrue(h.tilts.last() > 0.1f, "structure offset ${h.tilts.lastOrNull()}")
        assertEquals(0f, h.rig.tiltX, 1e-3f)
    }

    @Test
    fun tiltIsRelativeToTheHoldingAngleAndClamped() {
        val phone = Phone()
        phone.hold(topUp = 40f)
        val h = Harness(phone)
        h.frames(30)
        assertEquals(0f, h.rig.tiltY, 1e-3f)
        phone.hold(topUp = 100f)
        h.frames(2)
        assertEquals(1f, h.rig.tiltY, 1e-4f)
    }

    @Test
    fun noTiltIsSentBeforeTheHoldingAngleSettles() {
        val phone = Phone()
        val h = Harness(phone)
        h.frames(5)
        assertTrue(h.tilts.isEmpty(), "tilts ${h.tilts}")
        h.frames(30)
        assertTrue(h.tilts.isNotEmpty())
    }

    @Test
    fun aSwingingPhoneWaitsToTakeItsHoldingAngle() {
        val phone = Phone()
        val h = Harness(phone)
        // Unfolding or turning: the angle moves 3 degrees a frame for half a second.
        repeat(30) {
            phone.hold(topUp = it * 3f)
            h.frames(1)
        }
        assertTrue(h.tilts.isEmpty(), "took a holding angle mid-swing: tilts ${h.tilts}")
        // Then held still at 40: that, not an angle from the swing, reads level.
        phone.hold(topUp = 40f)
        h.frames(30)
        assertEquals(0f, h.rig.tiltY, 1e-3f)
    }

    @Test
    fun aShakenPhoneWaitsToTakeItsHoldingAngle() {
        val phone = Phone()
        val h = Harness(phone)
        repeat(30) {
            phone.kickX = if (it % 2 == 0) 8f else -8f
            h.frames(1)
        }
        assertTrue(h.tilts.isEmpty(), "took a holding angle while shaken: tilts ${h.tilts}")
        h.frames(30)
        assertTrue(h.tilts.isNotEmpty(), "never took one once still")
    }

    @Test
    fun aPhoneThatNeverSettlesStillGetsAHoldingAngle() {
        val phone = Phone()
        val h = Harness(phone)
        repeat(150) {
            phone.hold(topUp = 20f + 5f * sin(it * 0.5f))
            h.frames(1)
        }
        assertTrue(h.tilts.isNotEmpty(), "tilt never started on a phone that keeps moving")
    }

    @Test
    fun aSwitchToShakeWaitsForStillnessToo() {
        val phone = Phone()
        val h = Harness(phone)
        h.frames(30)
        // The tap that picks Shake rocks the phone.
        h.mode = CowbellMode.SHAKE
        repeat(10) {
            phone.hold(topUp = 10f + it * 3f)
            h.frames(1)
        }
        phone.hold(topUp = 40f)
        h.frames(30)
        assertEquals(0f, h.rig.tiltY, 1e-3f)
    }

    @Test
    fun modeChangeCapturesANewHoldingAngle() {
        val phone = Phone()
        val h = Harness(phone)
        h.frames(30)
        phone.hold(topUp = 25f)
        h.frames(2)
        assertTrue(h.rig.tiltY > 0.5f)
        h.mode = CowbellMode.SYNTH
        h.frames(2)
        assertEquals(0f, h.rig.tiltY, 1e-3f)
    }

    @Test
    fun aLongGapRecapturesTheHoldingAngle() {
        val phone = Phone()
        val h = Harness(phone)
        h.frames(30)
        phone.hold(topUp = 25f)
        h.frames(1, dt = 5.0)
        h.frames(30)
        assertEquals(0f, h.rig.tiltY, 1e-3f)
    }

    @Test
    fun motionOffLeavesEveryModeStill() {
        for (mode in CowbellMode.entries) {
            val phone = Phone()
            val h = Harness(phone, mode)
            h.motionOn = false
            h.frames(30)
            phone.hold(topUp = 30f, rightDown = 20f)
            h.frames(5)
            assertTrue(h.tilts.isEmpty(), "$mode tilts ${h.tilts}")
            assertEquals(0f, h.rig.tiltX)
            assertEquals(0f, h.rig.tiltY)
        }
    }

    @Test
    fun turningMotionOffLetsGoOfStructure() {
        val phone = Phone()
        val h = Harness(phone)
        h.frames(30)
        phone.hold(topUp = 25f)
        h.frames(2)
        assertTrue(h.tilts.last() > 0.1f)
        h.motionOn = false
        h.frames(2)
        assertEquals(0f, h.tilts.last())
        assertEquals(0f, h.rig.tiltY)
    }

    @Test
    fun withMotionOffAShakenPhoneLeavesTheFillAndAFlingMovesIt() {
        val phone = Phone()
        val h = Harness(phone, CowbellMode.SHAKE)
        h.motionOn = false
        h.frames(2)
        phone.kickX = 30f
        h.frames(1)
        val still = h.rig.physics!!.particles.map { it.vz }.average()
        assertEquals(0.0, still, 1e-3, "a shaken phone moved the fill: vz $still")
        h.rig.kick(0f, 15f)
        h.frames(1)
        val flung = h.rig.physics!!.particles.map { it.vx }.average()
        assertTrue(flung > 0.01, "a fling did not move the fill: vx $flung")
    }

    @Test
    fun turningMotionBackOnTakesANewHoldingAngle() {
        val phone = Phone()
        val h = Harness(phone)
        h.motionOn = false
        h.frames(30)
        phone.hold(topUp = 40f)
        h.frames(5)
        h.motionOn = true
        h.frames(30)
        assertEquals(0f, h.rig.tiltY, 1e-3f)
    }

    @Test
    fun pointerTiltDrivesTheViewWithoutMotion() {
        val h = Harness(Phone(available = false))
        h.rig.pointerTilt(1f, -1f)
        h.frames(2)
        assertEquals(1f, h.rig.tiltX)
        assertEquals(-1f, h.rig.tiltY)
        assertTrue(h.tilts.isEmpty(), "a desktop never writes STRUCTURE from the pointer")
    }

    @Test
    fun theLeanEasesBackWhenTheMouseLeaves() {
        val h = Harness(Phone(available = false))
        h.rig.pointerTilt(1f, -1f)
        h.frames(2)
        h.rig.pointerLeft()
        h.frames(1)
        assertTrue(h.rig.tiltX > 0f && h.rig.tiltX < 1f, "eases, no pop: ${h.rig.tiltX}")
        h.frames(30)
        assertEquals(0f, h.rig.tiltX, 1e-6f)
        assertEquals(0f, h.rig.tiltY, 1e-6f)
        h.rig.pointerTilt(0.5f, 0f)
        h.frames(1)
        assertEquals(0.5f, h.rig.tiltX, "back over the bell, the mouse leans it at once")
    }

    @Test
    fun motionIgnoresThePointerTilt() {
        val h = Harness(Phone())
        h.frames(30)
        h.rig.pointerTilt(1f, 1f)
        h.frames(2)
        assertEquals(0f, h.rig.tiltX, 1e-3f)
    }

    @Test
    fun cameraSwaysWithTiltAndNotWhileMorphing() {
        val h = Harness(Phone(available = false))
        h.rig.pointerTilt(1f, 0f)
        h.frames(60)
        val tap = org.balch.orpheus.features.cowbell.bell3d.BellCamera(pitch = 18f, yaw = -35f + 10.8f, roll = 0f)
        assertEquals(tap, h.rig.camera)
        h.morphing = true
        h.frames(1)
        assertNull(h.rig.camera)
    }

    @Test
    fun physicsExistsOnlyInShake() {
        val h = Harness(Phone(available = false))
        h.frames(3)
        assertNull(h.rig.physics)
        h.mode = CowbellMode.SHAKE
        h.frames(3)
        assertNotNull(h.rig.physics)
        h.mode = CowbellMode.TAP
        h.frames(3)
        assertNull(h.rig.physics)
    }

    @Test
    fun physicsHoldsStillDuringAMorph() {
        val h = Harness(Phone(available = false), mode = CowbellMode.SHAKE)
        h.frames(2)
        val before = h.rig.physics!!.particles.map { it.x to it.z }
        h.morphing = true
        h.rig.pointerTilt(1f, 1f)
        h.rig.kick(40f, 40f)
        h.frames(30)
        assertEquals(before, h.rig.physics!!.particles.map { it.x to it.z })
    }

    @Test
    fun tiltedDesktopFillRattlesAndSendsStrikes() {
        val h = Harness(Phone(available = false), mode = CowbellMode.SHAKE)
        h.rig.pointerTilt(1f, 0f)
        h.frames(240)
        assertTrue(h.strikes.isNotEmpty(), "no strikes")
        // Every word is a valid packed strike: the valid bit is bit 18.
        assertTrue(h.strikes.all { it.toInt() and (1 shl 18) != 0 }, "strikes ${h.strikes}")
    }

    @Test
    fun pointerTiltRightMovesTheFillTowardTheScreensRight() {
        // From above the bell's +z runs to the screen's left, so the fill gathers at -z.
        val h = Harness(Phone(available = false), mode = CowbellMode.SHAKE, fill = CowbellFill.BEADS)
        h.rig.pointerTilt(1f, 0f)
        h.frames(300)
        val mean = h.rig.physics!!.particles.map { it.z }.average()
        assertTrue(mean < -0.2, "mean z $mean")
    }

    @Test
    fun aShoveToTheRightMakesTheFillLagLeft() {
        val phone = Phone()
        val h = Harness(phone, mode = CowbellMode.SHAKE)
        h.frames(30)
        // The phone accelerates right, and linear acceleration points the way it moves.
        phone.kickX = 30f
        h.frames(1)
        // The bell's +z runs to the screen's left from above, and the fill lags that way.
        val vz = h.rig.physics!!.particles.map { it.vz }.average()
        assertTrue(vz > 0.01, "vz $vz")
    }

    @Test
    fun pointerKicksAreSummedPerFrameAndSpent() {
        val h = Harness(Phone(available = false), mode = CowbellMode.SHAKE)
        h.frames(2)
        // Down on the screen is the bell's -x, so a push down lags the fill to +x.
        h.rig.kick(0f, 7f)
        h.rig.kick(0f, 8f)
        h.frames(1)
        val vx = h.rig.physics!!.particles.map { it.vx }.average()
        assertTrue(vx > 0.012 && vx < 0.02, "vx $vx for a 15 px push")
        h.frames(1)
        val after = h.rig.physics!!.particles.map { it.vx }.average()
        assertTrue(after < vx, "the push is spent after one frame: $vx then $after")
    }

    @Test
    fun everyFillsStrikesFlashTheBellAndItDecays() {
        for (fill in CowbellFill.entries) {
            val h = Harness(Phone(available = false), mode = CowbellMode.SHAKE, fill = fill)
            h.rig.pointerTilt(1f, 0f)
            var peak = 0f
            repeat(300) {
                h.frames(1)
                peak = maxOf(peak, h.rig.flash)
            }
            assertTrue(peak > 0f && peak <= 1f, "$fill peak $peak")
            h.mode = CowbellMode.TAP
            h.frames(120)
            assertTrue(h.rig.flash < 0.01f, "$fill flash ${h.rig.flash} did not decay")
        }
    }

    @Test
    fun plungerAndReleaseReachThePhysics() {
        val h = Harness(Phone(available = false), mode = CowbellMode.SHAKE, fill = CowbellFill.BEADS)
        h.frames(2)
        val p = h.rig.physics!!
        val before = p.particles.map { it.vx }
        h.rig.release(-1f)
        assertTrue(p.particles.map { it.vx }.zip(before).all { (after, was) -> after > was }, "release pushes along +x")
    }
}
