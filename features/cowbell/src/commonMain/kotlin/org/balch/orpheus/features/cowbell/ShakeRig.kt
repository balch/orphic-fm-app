package org.balch.orpheus.features.cowbell

import org.balch.orpheus.features.cowbell.bell3d.BellCamera
import org.balch.orpheus.features.cowbell.bell3d.PlaneView
import org.balch.orpheus.features.cowbell.bell3d.forMode
import org.balch.orpheus.features.cowbell.bell3d.swayed
import org.balch.orpheus.features.cowbell.motion.MotionSample
import org.balch.orpheus.features.cowbell.motion.MotionSource
import org.balch.orpheus.features.cowbell.motion.TiltNeutral
import org.balch.orpheus.features.cowbell.motion.structureOffsetFromPitch
import org.balch.orpheus.features.cowbell.shake.FillPhysics
import org.balch.orpheus.features.cowbell.shake.FillStrikes
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** What the pointer gestures hand the fill. All distances are dp, all in display axes (y down). */
internal interface ShakeInput {
    /** The mouse over the bell box, each axis -1..1 from its centre. */
    fun pointerTilt(x: Float, y: Float)

    /** The mouse left the bell box, so the lean eases back to centre. */
    fun pointerLeft()

    /** Pointer movement, or a press's push: the bell is shoved by this much. */
    fun kick(dx: Float, dy: Float)

    /** The bend is held at [bend] (-1..1). */
    fun plunge(bend: Float)

    /** The bend let go from [bend]. */
    fun release(bend: Float)
}

internal object NoShakeInput : ShakeInput {
    override fun pointerTilt(x: Float, y: Float) {}
    override fun pointerLeft() {}
    override fun kick(dx: Float, dy: Float) {}
    override fun plunge(bend: Float) {}
    override fun release(bend: Float) {}
}

/** A shake of the phone, 1 m/s² of kick for this many of the physics' pointer-px units. Tuning. */
internal const val MOTION_KICK_PER_MS2 = 1f

/** A pointer dp of travel for this many of the physics' pointer-px units. The prototype's is 1. */
internal const val POINTER_KICK_PER_DP = 1f

/** A press at the bell's edge pushes the fill like this many dp of pointer travel. 24 was too soft on the Fold. */
internal const val PRESS_KICK_DP = 48f

/** A finger's travel kicks this much harder than a mouse's, so a fling on a phone throws the fill. */
internal const val TOUCH_KICK_GAIN = 2.5f

/** Degrees of phone tilt that tip the fill and the view all the way. */
private const val FULL_TILT_DEGREES = 35f

/** A gap this long between frames means the phone was put down: the holding angle is forgotten. */
private const val RESUME_GAP_SECONDS = 0.5

/** Wait this long after the sensors start before taking the holding angle, so a first sample is real. */
private const val SETTLE_SECONDS = 0.3

/**
 * The holding angle is taken only while gravity turns slower than this (about 57 degrees a second)
 * and the kick stays under [STILL_KICK_MS2], so an unfold or the tap that picks a mode is not level.
 */
private const val STILL_GRAVITY_PER_SECOND = 1f
private const val STILL_KICK_MS2 = 1.5f

/** A phone that never holds still takes its angle anyway after this long, as it did before the wait. */
private const val STILL_WAIT_SECONDS = 1.5

/** The view's lean eases in this fast (full in 1/4 s) after a morph, and out when the mouse leaves, so it never pops. */
private const val SWAY_PER_SECOND = 4f

/** The bell flash decays by this per 60 fps frame, as the prototype's. */
private const val FLASH_DECAY = 0.94

/**
 * Everything the bell's frame loop does besides drawing, in plain fields (a frame must not write
 * snapshot state): tilt from the phone's sensors, or from the pointer on a desktop; the swayed
 * camera; and, in SHAKE, the fill's physics with its strikes sent on.
 */
internal class ShakeRig(private val motion: MotionSource, private val random: Random = Random.Default) : ShakeInput {
    private val sample = MotionSample()
    private val neutral = TiltNeutral()
    private val strikes = FillStrikes()
    private val view = PlaneView()

    /** The fill's physics: present only in SHAKE. */
    var physics: FillPhysics? = null
        private set

    /** The camera to draw this frame, swayed by the tilt; null while a morph moves the camera. */
    var camera: BellCamera? = null
        private set

    var tiltX = 0f
        private set
    var tiltY = 0f
        private set

    /** The bell's flash from the fill's strikes, 0..1, decaying. Read in the draw phase. */
    var flash = 0f
        private set

    private var physicsFill: CowbellFill? = null
    private var lastSeconds = -1.0
    private var lastMode: CowbellMode? = null
    private var lastUsingMotion: Boolean? = null
    private var settle = SETTLE_SECONDS
    private var stillWait = 0.0
    private var lastGravityX = Float.NaN
    private var lastGravityY = 0f
    private var lastGravityZ = 0f
    private var swayGain = 0f

    private var pointerX = 0f
    private var pointerY = 0f
    private var pointerGone = false
    private var pointerGain = 1f
    private var kickDpX = 0f
    private var kickDpY = 0f

    // Per-frame scratch, so the strike sink below captures nothing.
    private var sinkActions: CowbellPanelActions? = null
    private var sinkFactor = 0f
    private val sendStrike: (Float) -> Unit = { packed ->
        val actions = sinkActions
        if (actions != null) actions.onFillStrike(packed)
        flash = min(1f, flash + (packed.toInt() and 0x7F) / 127f * sinkFactor)
    }

    override fun pointerTilt(x: Float, y: Float) {
        pointerX = x.coerceIn(-1f, 1f)
        pointerY = y.coerceIn(-1f, 1f)
        pointerGone = false
        pointerGain = 1f
    }

    override fun pointerLeft() {
        pointerGone = true
    }

    override fun kick(dx: Float, dy: Float) {
        kickDpX += dx
        kickDpY += dy
    }

    override fun plunge(bend: Float) {
        physics?.plunge(bend)
    }

    override fun release(bend: Float) {
        physics?.release(bend)
    }

    /**
     * One frame at [seconds] on the bell's clock. [morphing] is a camera move in progress: the
     * fill holds still then and the camera is the morph's own. [base] is the mode's resting camera.
     * [motionOn] lets the phone's tilt and kicks play the bell; off, only the pointer moves the fill.
     */
    fun frame(
        seconds: Double,
        mode: CowbellMode,
        fill: CowbellFill,
        morphing: Boolean,
        actions: CowbellPanelActions,
        base: BellCamera = BellCamera.forMode(mode),
        motionOn: Boolean = true,
    ) {
        val dt = if (lastSeconds < 0.0) 0.0 else max(0.0, seconds - lastSeconds)
        lastSeconds = seconds
        flash *= FLASH_DECAY.pow(dt * 60.0).toFloat()

        if (dt > RESUME_GAP_SECONDS) {
            neutral.reset()
            settle = SETTLE_SECONDS
            stillWait = 0.0
        }
        if (mode != lastMode) {
            if (lastMode != null) {
                neutral.reset()
                settle = 0.0
                stillWait = 0.0
            }
            lastMode = mode
        }
        val usingMotion = motion.available && motionOn
        if (usingMotion != lastUsingMotion) {
            if (lastUsingMotion != null) {
                neutral.reset()
                settle = 0.0
                stillWait = 0.0
                // Tilt lets go of STRUCTURE, so the bell does not keep the last angle it wrote.
                if (!usingMotion && motion.available) actions.onTilt(0f)
            }
            lastUsingMotion = usingMotion
        }

        // 1 and 2: tilt and kick, in display axes (y down), then the tilt to STRUCTURE.
        var kickX = kickDpX * POINTER_KICK_PER_DP
        var kickY = kickDpY * POINTER_KICK_PER_DP
        kickDpX = 0f
        kickDpY = 0f
        if (usingMotion) {
            motion.read(sample)
            val still = isStill(dt)
            if (!neutral.captured) {
                settle -= dt
                if (settle <= 0.0) {
                    stillWait += dt
                    // A move restarts the average, so every sample in it is from a still phone.
                    if (still || stillWait >= STILL_WAIT_SECONDS) neutral.capture(sample) else neutral.reset()
                }
            }
            if (neutral.captured) {
                val pitch = neutral.pitchDegrees(sample)
                // A sample is the reaction to gravity, so a dropped right edge reads a negative x.
                tiltX = (-neutral.rollDegrees(sample) / FULL_TILT_DEGREES).coerceIn(-1f, 1f)
                tiltY = (pitch / FULL_TILT_DEGREES).coerceIn(-1f, 1f)
                actions.onTilt(structureOffsetFromPitch(pitch))
            } else {
                tiltX = 0f
                tiltY = 0f
            }
            // Linear acceleration points the way the phone moves, like a pointer's travel, and the
            // physics lags the fill against a kick.
            kickX += sample.kickX * MOTION_KICK_PER_MS2
            kickY += sample.kickY * MOTION_KICK_PER_MS2
        } else {
            if (pointerGone) pointerGain = max(0f, pointerGain - dt.toFloat() * SWAY_PER_SECOND)
            tiltX = pointerX * pointerGain
            tiltY = pointerY * pointerGain
        }

        // 4: the camera. A morph owns it, and the lean eases back in after.
        swayGain = if (morphing) 0f else min(1f, swayGain + dt.toFloat() * SWAY_PER_SECOND)
        camera = if (morphing) null else base.swayed(tiltX * swayGain, tiltY * swayGain, mode)

        // 3: the fill.
        if (mode != CowbellMode.SHAKE) {
            physics = null
            physicsFill = null
            return
        }
        if (morphing) return
        var physics = physics
        if (physics == null || physicsFill != fill) {
            physics = FillPhysics(fill, random)
            this.physics = physics
            physicsFill = fill
        }
        advance(physics, dt, fill, kickX, kickY, camera ?: base, actions)
    }

    /** True when [sample] turned less than the still limits since the last frame [dt] ago. */
    private fun isStill(dt: Double): Boolean {
        val dx = sample.gravityX - lastGravityX
        val dy = sample.gravityY - lastGravityY
        val dz = sample.gravityZ - lastGravityZ
        val first = lastGravityX.isNaN()
        lastGravityX = sample.gravityX
        lastGravityY = sample.gravityY
        lastGravityZ = sample.gravityZ
        if (first || dt <= 0.0) return false
        val turn = sqrt(dx * dx + dy * dy + dz * dz) / dt.toFloat()
        val kick = sqrt(sample.kickX * sample.kickX + sample.kickY * sample.kickY)
        return turn <= STILL_GRAVITY_PER_SECOND && kick <= STILL_KICK_MS2
    }

    private fun advance(
        physics: FillPhysics,
        dt: Double,
        fill: CowbellFill,
        kickX: Float,
        kickY: Float,
        camera: BellCamera,
        actions: CowbellPanelActions,
    ) {
        // The display's down and right, as the bell's x and z lie on the screen now.
        view.set(camera, 0f)
        view.project(0f, 0f)
        val ox = view.px
        val oy = view.py
        view.project(1f, 0f)
        var xx = view.px - ox
        var xy = view.py - oy
        view.project(0f, 1f)
        var zx = view.px - ox
        var zy = view.py - oy
        val xn = sqrt(xx * xx + xy * xy)
        val zn = sqrt(zx * zx + zy * zy)
        if (xn > 0f) { xx /= xn; xy /= xn }
        if (zn > 0f) { zx /= zn; zy /= zn }

        sinkActions = actions
        sinkFactor = flashFactor(fill)
        physics.advance(
            seconds = dt,
            gravityX = tiltX * xx + tiltY * xy,
            gravityZ = tiltX * zx + tiltY * zy,
            kickX = kickX * xx + kickY * xy,
            kickZ = kickX * zx + kickY * zy,
            out = strikes,
        )
        strikes.drain(sendStrike)
        sinkActions = null
    }

    private fun flashFactor(fill: CowbellFill): Float = when (fill) {
        CowbellFill.SEEDS -> 0.15f
        CowbellFill.JINGLES -> 0.35f
        CowbellFill.BEADS -> 0.4f
        CowbellFill.MARBLE -> 0.6f
    }
}
