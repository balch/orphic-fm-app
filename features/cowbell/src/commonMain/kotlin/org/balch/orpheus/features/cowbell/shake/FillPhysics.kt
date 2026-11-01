package org.balch.orpheus.features.cowbell.shake

import org.balch.orpheus.features.cowbell.bell3d.MX
import org.balch.orpheus.features.cowbell.bell3d.MZ
import org.balch.orpheus.features.cowbell.bell3d.TX
import org.balch.orpheus.features.cowbell.bell3d.TZ
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.strikeKind
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/** Height of the fill's plane in the bell's local units, y up the axis. */
const val FILL_PLANE_Y = -0.55f

/** One piece of the fill, in the bell's x/z plane. Velocities are in the prototype's per-60 fps-frame units. */
class FillParticle internal constructor(val r: Float) {
    var x = 0f
        internal set
    var z = 0f
        internal set
    var vx = 0f
        internal set
    var vz = 0f
        internal set
    var spin = 0f
        internal set

    internal var asleep = false
    internal var sleepAx = 0f
    internal var sleepAz = 0f
    internal var touched = false
}

/**
 * The SHAKE fill rattling in the bell's mouth cross-section (the approved prototype's `physics()`,
 * run at a fixed 120 Hz). Velocities, caps and strike thresholds keep the prototype's per-60 fps
 * frame units, so a step moves a particle by half its velocity and per-step velocity terms halve.
 *
 * [advance] takes the bell-plane gravity (-1..1) and the kick (the prototype's per-frame pointer
 * displacement; `-k * 0.0011` of velocity) already converted to the plane's x/z axes.
 */
class FillPhysics(private val fill: CowbellFill, private val random: Random) {

    private val spec = when (fill) {
        CowbellFill.MARBLE -> Spec(1, 0.075f)
        CowbellFill.BEADS -> Spec(10, 0.045f)
        CowbellFill.SEEDS -> Spec(45, 0.019f)
        CowbellFill.JINGLES -> Spec(6, 0.055f)
    }
    private val heavy = if (fill == CowbellFill.MARBLE) 1f else 0.8f
    private val kind = fill.strikeKind

    private val list: List<FillParticle> = List(spec.count) { _ ->
        val radius = if (fill == CowbellFill.SEEDS) spec.radius * (0.8f + 0.4f * random.nextFloat()) else spec.radius
        FillParticle(radius).also {
            it.x = (random.nextFloat() - 0.5f) * 0.6f
            it.z = (random.nextFloat() - 0.5f) * 0.4f
            it.spin = random.nextFloat() * 6f
        }
    }
    private val items = list.toTypedArray()

    /** The pieces, created once; drawn from [FillParticle.x], `z`, `r` and `spin`. */
    val particles: List<FillParticle> get() = list

    private var gravityX = 0f
    private var gravityZ = 0f
    private var pendingKickX = 0f
    private var pendingKickZ = 0f
    private var bend = 0f
    private var accumulated = 0.0
    private var clock = 0.0
    private var lastBodyHit = -1.0

    /** The plunger is held at [bend] (-1..1); gravity along x gains `bend * 0.003` per frame. */
    fun plunge(bend: Float) {
        this.bend = sane(bend, 1f)
    }

    /** Lets go of the plunger and kicks every piece along x, harder the further it was pulled. */
    fun release(bendAtRelease: Float) {
        val b = sane(bendAtRelease, 1f)
        bend = 0f
        val magnitude = abs(b)
        if (magnitude == 0f) return
        val scale = if (magnitude < NUDGE_BELOW) RELEASE_KICK / 3f else RELEASE_KICK
        val dv = -sign(b) * magnitude * scale
        for (p in items) {
            p.vx += dv
            p.asleep = false
        }
    }

    fun advance(
        seconds: Double,
        gravityX: Float,
        gravityZ: Float,
        kickX: Float,
        kickZ: Float,
        out: FillStrikes,
    ) {
        if (seconds.isNaN() || seconds <= 0.0) return
        this.gravityX = sane(gravityX, GRAVITY_LIMIT)
        this.gravityZ = sane(gravityZ, GRAVITY_LIMIT)
        pendingKickX += sane(kickX, KICK_LIMIT)
        pendingKickZ += sane(kickZ, KICK_LIMIT)
        accumulated += min(seconds, MAX_ADVANCE)
        val steps = floor(accumulated / STEP + 1e-9).toInt()
        if (steps == 0) return
        accumulated = (accumulated - steps * STEP).coerceAtLeast(0.0)
        // The call's kick is a displacement, so spread it over the steps this call runs.
        val kx = pendingKickX / steps
        val kz = pendingKickZ / steps
        pendingKickX = 0f
        pendingKickZ = 0f
        repeat(steps) { step(kx, kz, out) }
    }

    private fun step(kickX: Float, kickZ: Float, out: FillStrikes) {
        clock += STEP
        val ax = gravityX * GRAVITY * heavy + bend * PLUNGE
        val az = gravityZ * GRAVITY * heavy
        val kicked = kickX != 0f || kickZ != 0f
        val dvx = ax * 0.5f - kickX * KICK
        val dvz = az * 0.5f - kickZ * KICK
        for (p in items) {
            p.touched = false
            if (p.asleep) {
                if (!kicked && abs(ax - p.sleepAx) <= WAKE_ACCEL && abs(az - p.sleepAz) <= WAKE_ACCEL) continue
                p.asleep = false
            }
            p.vx = (p.vx + dvx) * DAMPING
            p.vz = (p.vz + dvz) * DAMPING
            val speed = sqrt(p.vx * p.vx + p.vz * p.vz)
            if (speed > SPEED_CAP) {
                val k = SPEED_CAP / speed
                p.vx *= k
                p.vz *= k
            }
            p.x += p.vx * 0.5f
            p.z += p.vz * 0.5f
            p.spin += speed * 4f

            val hx = HALF_X - p.r
            val hz = HALF_Z - p.r
            var hit = 0f
            var brightness = 0f
            if (abs(p.x) > hx) {
                hit = abs(p.vx)
                brightness = BRIGHT_X
                // Resting against a wall gravity presses on: stay put instead of buzzing.
                p.vx = if (hit < WALL_THRESHOLD && ax * p.x > 0f) 0f else p.vx * -WALL_RESTITUTION
                p.x = sign(p.x) * hx
            }
            if (abs(p.z) > hz) {
                if (abs(p.vz) > hit) {
                    hit = abs(p.vz)
                    brightness = BRIGHT_Z
                }
                p.vz = if (abs(p.vz) < WALL_THRESHOLD && az * p.z > 0f) 0f else p.vz * -WALL_RESTITUTION
                p.z = sign(p.z) * hz
            }
            if (hit > WALL_THRESHOLD) wallStrike(hit, brightness, out)
        }

        for (i in items.indices) {
            val a = items[i]
            for (j in i + 1 until items.size) {
                val b = items[j]
                if (a.asleep && b.asleep) continue
                val dx = b.x - a.x
                val dz = b.z - a.z
                val minDist = a.r + b.r
                if (abs(dx) >= minDist || abs(dz) >= minDist) continue
                val d = sqrt(dx * dx + dz * dz)
                if (d <= 0f || d >= minDist) continue
                val nx = dx / d
                val nz = dz / d
                val push = (minDist - d) / 2f
                a.x -= nx * push
                a.z -= nz * push
                b.x += nx * push
                b.z += nz * push
                a.asleep = false
                b.asleep = false
                a.touched = true
                b.touched = true
                val rv = (b.vx - a.vx) * nx + (b.vz - a.vz) * nz
                if (rv < 0f) {
                    val impulse = -rv * PARTICLE_RESTITUTION
                    a.vx -= impulse * nx
                    a.vz -= impulse * nz
                    b.vx += impulse * nx
                    b.vz += impulse * nz
                    if (-rv > PARTICLE_THRESHOLD) {
                        out.add(min(1f, -rv / PARTICLE_NORM) * PARTICLE_GAIN, kind, color(BRIGHT_PARTICLE))
                    }
                }
            }
        }

        if (kicked) return
        for (p in items) {
            if (p.asleep || p.touched) continue
            if (p.vx * p.vx + p.vz * p.vz >= SLEEP_SPEED * SLEEP_SPEED) continue
            if (!settled(p.x, ax, HALF_X - p.r) || !settled(p.z, az, HALF_Z - p.r)) continue
            p.asleep = true
            p.sleepAx = ax
            p.sleepAz = az
        }
    }

    /** True when the axis acceleration is negligible, or the wall it pushes toward is already there. */
    private fun settled(pos: Float, accel: Float, limit: Float): Boolean =
        abs(accel) <= FREE_ACCEL || (abs(pos) >= limit - 1e-5f && accel * pos > 0f)

    private fun wallStrike(hit: Float, brightness: Float, out: FillStrikes) {
        val velocity = min(1f, hit / WALL_NORM)
        val body = when {
            fill == CowbellFill.MARBLE -> true
            velocity > BODY_VELOCITY && clock - lastBodyHit >= BODY_GAP -> {
                lastBodyHit = clock
                true
            }
            else -> false
        }
        out.add(velocity, if (body) 0 else kind, color(brightness))
    }

    private fun color(brightness: Float): Int = 1 + (brightness * 126f).roundToInt()

    private class Spec(val count: Int, val radius: Float)

    private companion object {
        const val STEP = 1.0 / 120.0
        const val MAX_ADVANCE = 0.1

        // Prototype per-frame constants. Per-step: the velocity terms halve, damping is a square root.
        const val GRAVITY = 0.0016f
        const val KICK = 0.0011f
        const val PLUNGE = 0.003f
        const val SPEED_CAP = 0.09f
        const val WALL_RESTITUTION = 0.6f
        const val PARTICLE_RESTITUTION = 0.85f
        val DAMPING = sqrt(0.988).toFloat()

        const val WALL_THRESHOLD = 0.005f
        const val WALL_NORM = 0.04f
        const val PARTICLE_THRESHOLD = 0.008f
        const val PARTICLE_NORM = 0.05f
        const val PARTICLE_GAIN = 0.7f
        const val BRIGHT_X = 0.75f
        const val BRIGHT_Z = 0.3f
        const val BRIGHT_PARTICLE = 0.6f

        const val BODY_VELOCITY = 0.55f
        const val BODY_GAP = 0.08

        const val RELEASE_KICK = 0.06f
        const val NUDGE_BELOW = 0.15f

        const val SLEEP_SPEED = 1e-4f
        const val FREE_ACCEL = 0.00003f
        const val WAKE_ACCEL = 0.00008f

        const val GRAVITY_LIMIT = 2f
        const val KICK_LIMIT = 400f

        /** Plane half-extents: the top and mouth rims lerped to the fill plane's height. */
        private const val T = (1f - FILL_PLANE_Y) / 2f
        val HALF_X = TX + (MX - TX) * T
        val HALF_Z = TZ + (MZ - TZ) * T

        fun sane(v: Float, limit: Float): Float = if (v.isNaN()) 0f else v.coerceIn(-limit, limit)
    }
}
