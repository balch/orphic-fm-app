package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal const val LIQUID_SECONDS = 2.2
internal const val PLAIN_TURN_SECONDS = 0.9

/** A camera move on the wall clock, so frame rate never changes how long it takes. */
internal class BellTransition(
    val from: BellCamera,
    val to: BellCamera,
    val startSeconds: Double,
    val durationSeconds: Double,
)

/** A transition with no duration is done at once, never NaN. */
internal fun BellTransition.progress(nowSeconds: Double): Float =
    if (durationSeconds <= 0.0) 1f else ((nowSeconds - startSeconds) / durationSeconds).toFloat().coerceIn(0f, 1f)

internal fun BellTransition.isDone(nowSeconds: Double): Boolean = progress(nowSeconds) >= 1f

internal fun BellTransition.camera(nowSeconds: Double): BellCamera =
    from.lerp(to, ease(progress(nowSeconds)))

/** Cubic in-out. */
internal fun ease(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

/** The mesh fades out as the pool forms and back in as it settles; 0 in between. */
internal fun meshAlpha(p: Float): Float = when {
    p < 0.12f -> 1f - p / 0.12f
    p > 0.9f -> ((p - 0.9f) / 0.1f).coerceAtMost(1f)
    else -> 0f
}

/** The liquid layer's opacity: it never fully covers the mesh while that is visible. */
internal fun liquidAlpha(p: Float): Float = 1f - meshAlpha(p) * 0.85f

/**
 * One frame of the liquid layer in canvas px. `poly` holds `polyCount` (x, y) pairs, then zeros.
 * Each `drops` entry is x, y, radius, 0; a radius of 0 means no drop.
 */
internal class LiquidFrame(
    val poly: FloatArray,
    val polyCount: Int,
    val drops: FloatArray,
    val inflate: Float,
)

private const val POLY_MAX = 16
private const val DROP_COUNT = 8
private const val BACK_OUT_C1 = 1.9f

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun backOut(x: Float): Float {
    val c3 = BACK_OUT_C1 + 1f
    return 1f + c3 * (x - 1f).pow(3) + BACK_OUT_C1 * (x - 1f).pow(2)
}

private fun drain(p: Float): Float = smoothstep(0.04f, 0.42f, p)

private fun refill(p: Float): Float = backOut(((p - 0.55f) / 0.45f).coerceIn(0f, 1f))

/** How far the pool has shrunk toward its centre: 1 at rest, dips on the drain, overshoots on refill. */
internal fun poolScale(p: Float): Float = 1f - 0.7f * drain(p) * (1f - refill(p))

/** Monotone chain, counter-clockwise in a y-up frame. */
internal fun convexHull(points: List<Offset>): List<Offset> {
    val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (p in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0f) lower.removeAt(lower.lastIndex)
        lower.add(p)
    }
    val upper = ArrayList<Offset>()
    for (p in sorted.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0f) upper.removeAt(upper.lastIndex)
        upper.add(p)
    }
    return lower.dropLast(1) + upper.dropLast(1)
}

private val SILHOUETTE: List<Vec3> = TOP + MOUTH + listOf(HANDLE[2], HANDLE[8], HANDLE[14])

/** Prototype lengths are half-res px at 125 px per unit; this is that unit in canvas px. */
internal const val PROTOTYPE_PX_PER_UNIT = 125f

/** The pool and its drops for morph progress `p`, placed through `camera` and `projector`. */
internal fun liquidFrame(projector: BellProjector, camera: BellCamera, p: Float, timeSeconds: Float): LiquidFrame {
    val unit = projector.scale / PROTOTYPE_PX_PER_UNIT
    val t = timeSeconds
    val hull = convexHull(SILHOUETTE.map { v -> projector.project(camera.rotate(v)).let { Offset(it.x, it.y) } })
        .take(POLY_MAX)

    val centre = Offset(hull.map { it.x }.average().toFloat(), hull.map { it.y }.average().toFloat())
    val s = poolScale(p)
    val wobble = sin(PI.toFloat() * p) * 5f * unit
    val poly = FloatArray(POLY_MAX * 2)
    hull.forEachIndexed { i, pt ->
        poly[i * 2] = centre.x + (pt.x - centre.x) * s + wobble * sin(t * 5f + i * 1.7f)
        poly[i * 2 + 1] = centre.y + (pt.y - centre.y) * s + wobble * cos(t * 4.3f + i * 2.3f)
    }

    val drops = FloatArray(DROP_COUNT * 4)
    for (i in 0 until DROP_COUNT) {
        val out = smoothstep(0.06f + 0.025f * i, 0.38f + 0.02f * i, p)
        val back = smoothstep(0.52f + 0.025f * i, 0.86f, p)
        val w = out * (1f - back)
        val ph = (2.0 * PI * i / DROP_COUNT).toFloat() + 4.2f * p
        val orbit = Vec3(
            0.78f * cos(ph),
            -0.55f + 1.1f * ((i * 3) % DROP_COUNT) / DROP_COUNT + 0.12f * sin(3f * t + i),
            0.78f * sin(ph),
        )
        val q = projector.project(camera.rotate(orbit * w))
        val radius = (0.13f + 0.04f * sin(2.1f * i)) * sqrt(min(1f, 1.4f * w)) * q.s * projector.scale
        drops[i * 4] = q.x
        drops[i * 4 + 1] = q.y
        drops[i * 4 + 2] = radius
    }

    val inflate = (10f + 12f * drain(p) * (1f - refill(p))) * unit
    return LiquidFrame(poly, hull.size, drops, inflate)
}
