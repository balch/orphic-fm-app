package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Size
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point or direction in bell-local units, y up the bell axis. */
internal data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun normalized(): Vec3 {
        val len = sqrt(this dot this)
        return if (len == 0f) this else this * (1f / len)
    }
}

/** View angles in degrees; `zoom` multiplies the fitted scale; `shiftY` moves the bell down the box, in px. */
internal data class BellCamera(
    val pitch: Float,
    val yaw: Float,
    val roll: Float,
    val zoom: Float = 1f,
    val shiftY: Float = 0f,
) {
    fun lerp(to: BellCamera, t: Float): BellCamera = BellCamera(
        pitch = pitch + (to.pitch - pitch) * t,
        yaw = yaw + (to.yaw - yaw) * t,
        roll = roll + (to.roll - roll) * t,
        zoom = zoom + (to.zoom - zoom) * t,
        shiftY = shiftY + (to.shiftY - shiftY) * t,
    )

    companion object
}

/** Degrees of pitch a full tilt adds in SHAKE, and everywhere else; yaw gets 1.2 times that. */
private const val SWAY_SHAKE = 4f
private const val SWAY_OTHER = 9f

/**
 * This view leaned by a tilt, each axis -1..1 (more is clamped, NaN is none): tilt y pitches it,
 * tilt x turns it. SHAKE leans less, so the fill stays in view.
 */
internal fun BellCamera.swayed(tiltX: Float, tiltY: Float, mode: CowbellMode): BellCamera {
    val k = if (mode == CowbellMode.SHAKE) SWAY_SHAKE else SWAY_OTHER
    val x = if (tiltX.isNaN()) 0f else tiltX.coerceIn(-1f, 1f)
    val y = if (tiltY.isNaN()) 0f else tiltY.coerceIn(-1f, 1f)
    return copy(pitch = pitch + y * k, yaw = yaw + x * k * 1.2f)
}

/** TAP and SHAKE come from the approved prototype; SYNTH is a low, close side view. */
internal fun BellCamera.Companion.forMode(mode: CowbellMode): BellCamera = when (mode) {
    CowbellMode.TAP -> BellCamera(pitch = 18f, yaw = -35f, roll = 0f)
    CowbellMode.SHAKE -> BellCamera(pitch = -90f, yaw = 0f, roll = 90f, zoom = SHAKE_ZOOM)
    CowbellMode.SYNTH -> BellCamera(pitch = -6f, yaw = 62f, roll = 0f, zoom = 1.15f)
}

/** The top-down Shake view fills its frame; fitted like the side views, the bell read small. */
private const val SHAKE_ZOOM = 1.6f

private const val D2R = (PI / 180.0).toFloat()

/** Yaw about y, then pitch about x, then roll about z, as the prototype's `xf`. */
internal fun BellCamera.rotate(v: Vec3): Vec3 {
    val cy = cos(yaw * D2R)
    val sy = sin(yaw * D2R)
    val x1 = v.x * cy + v.z * sy
    val z1 = -v.x * sy + v.z * cy
    val cp = cos(pitch * D2R)
    val sp = sin(pitch * D2R)
    val y2 = v.y * cp - z1 * sp
    val z2 = v.y * sp + z1 * cp
    val cr = cos(roll * D2R)
    val sr = sin(roll * D2R)
    return Vec3(x1 * cr - y2 * sr, x1 * sr + y2 * cr, z2)
}

internal const val CAMD = 4.2f
internal const val FOC = 4.2f

/** A projected point: pixel position plus perspective scale `s`. */
internal data class Projected(val x: Float, val y: Float, val s: Float)

/**
 * Perspective projection into a box. The scale is px per model unit, fitted so every view of the
 * bell stays inside the box; the divisors (2.4 wide, 3.6 tall) are the plan's, unchanged.
 */
internal class BellProjector(private val size: Size, private val camera: BellCamera) {
    val scale: Float = min(size.width / WIDE_UNITS, size.height / TALL_UNITS) * camera.zoom
    val isEmpty: Boolean = !(size.width >= 1f && size.height >= 1f)

    fun project(v: Vec3): Projected {
        val s = FOC / (CAMD - v.z)
        return Projected(size.width / 2f + v.x * s * scale, size.height / 2f + camera.shiftY - v.y * s * scale, s)
    }

    private companion object {
        const val WIDE_UNITS = 2.4f
        const val TALL_UNITS = 3.6f
    }
}

internal const val TX = 0.32f
internal const val TZ = 0.2f
internal const val MX = 0.62f
internal const val MZ = 0.38f

/** The top quad at y = 1. */
internal val TOP = listOf(Vec3(-TX, 1f, -TZ), Vec3(TX, 1f, -TZ), Vec3(TX, 1f, TZ), Vec3(-TX, 1f, TZ))

/** The open mouth quad at y = -1. */
internal val MOUTH = listOf(Vec3(-MX, -1f, -MZ), Vec3(MX, -1f, -MZ), Vec3(MX, -1f, MZ), Vec3(-MX, -1f, MZ))

/** The top, then the four sides. */
internal val FACES: List<List<Vec3>> = listOf(
    TOP,
    listOf(TOP[0], TOP[1], MOUTH[1], MOUTH[0]),
    listOf(TOP[1], TOP[2], MOUTH[2], MOUTH[1]),
    listOf(TOP[2], TOP[3], MOUTH[3], MOUTH[2]),
    listOf(TOP[3], TOP[0], MOUTH[0], MOUTH[3]),
)

/** The handle arc over the top: 17 points of a half circle in the z = 0 plane. */
internal val HANDLE: List<Vec3> = List(17) { i ->
    val th = (PI * i / 16).toFloat()
    Vec3(0.22f * cos(th), 1f + 0.3f * sin(th), 0f)
}

internal val LIGHT: Vec3 = Vec3(-0.4f, 0.6f, 0.75f).normalized()

/** Stretches the bell toward the bend and squeezes it across. `pull` is clamped to -1..1. */
internal fun bend(v: Vec3, pull: Float): Vec3 {
    val squeeze = bendSqueeze(pull)
    return Vec3(v.x * squeeze, bendY(v.y, pull), v.z * squeeze)
}

/** How much a pull narrows the bell across: x and z scale by this. */
internal fun bendSqueeze(pull: Float): Float = 1f - 0.024f * abs(pull.coerceIn(-1f, 1f))

/** Where a pull stretches height [y] to. */
internal fun bendY(y: Float, pull: Float): Float {
    val p = pull.coerceIn(-1f, 1f)
    val anchor = if (p >= 0f) -1f else 1.3f
    return anchor + (y - anchor) * (1f + 0.08f * abs(p))
}
