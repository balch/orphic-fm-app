package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.cowbell.BellMorphStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal const val SHARDS_SECONDS = 1.6

/** Shards per face side: each face splits into a 4x4 grid. */
internal const val SHARD_GRID = 4
internal val SHARD_COUNT: Int = FACES.size * SHARD_GRID * SHARD_GRID

/** How far the camera backs off at full burst, so the flying shards stay in the bell's box. */
private const val SHARD_BACKOFF = 0.45f

/** Prototype handle width (18 px at 250 px per unit) in model units. */
private const val SHARD_HANDLE_WIDTH = 0.072f
private const val HANDLE_LIFT = 0.6f

/** Each shard is a fill quad and four edge quads; the handle is a ribbon of point pairs. */
private const val SHARD_VERTS = 4 + 4 * 4
private val HANDLE_VERTS: Int = HANDLE.size * 2

/** False only on Android below 10, whose hardware canvas drops `drawVertices`. */
internal expect fun shardsSupported(): Boolean

/** The morph's length for [style]; an unsupported style turns plainly. */
internal fun morphSeconds(style: BellMorphStyle, liquidSupported: Boolean, shardsSupported: Boolean): Double = when {
    style == BellMorphStyle.SHARDS && shardsSupported -> SHARDS_SECONDS
    style == BellMorphStyle.LIQUID && liquidSupported -> LIQUID_SECONDS
    else -> PLAIN_TURN_SECONDS
}

/** The burst: 0 at both ends, 1 half way, as the prototype's `sin(PI * p)`. */
internal fun shardAmount(p: Float): Float = max(0f, sin(PI.toFloat() * p.coerceIn(0f, 1f)))

internal fun shardZoom(m: Float): Float = 1f / (1f + SHARD_BACKOFF * m)

/** The resting mesh lies over the shards near either end, so the hand-off does not pop. */
internal fun shardMeshAlpha(m: Float): Float = (1f - m / 0.25f).coerceIn(0f, 1f)

/** One shard at rest, in bell-local units. `hue` indexes [BellPalette]. */
internal class Shard(val corners: List<Vec3>, val normal: Vec3, val centre: Vec3, val seed: Float, val hue: Int)

private fun bilinear(f: List<Vec3>, u: Float, v: Float): Vec3 {
    val a = f[0] + (f[1] - f[0]) * u
    val b = f[3] + (f[2] - f[3]) * u
    return a + (b - a) * v
}

/** The prototype's `SHARDS`, with fixed seeds so every run bursts the same way. */
internal val SHARD_TABLE: List<Shard> = run {
    val random = Random(0xBE11)
    FACES.flatMapIndexed { fi, f ->
        val n = faceNormal(f)
        List(SHARD_GRID * SHARD_GRID) { k ->
            val i = k / SHARD_GRID
            val j = k % SHARD_GRID
            val g = SHARD_GRID.toFloat()
            val q = listOf(
                bilinear(f, i / g, j / g),
                bilinear(f, (i + 1) / g, j / g),
                bilinear(f, (i + 1) / g, (j + 1) / g),
                bilinear(f, i / g, (j + 1) / g),
            )
            Shard(q, n, centroid(q), random.nextFloat(), (fi * 3 + i + j) % BellPalette.size)
        }
    }
}

/**
 * Corner [k] of [shard] at burst [m], bell-local and unbent: shrunk by `1 - m/2` toward its
 * centre, spun about the face normal, then pushed out along it. Written to `out[0..2]`.
 */
internal fun shardCorner(shard: Shard, k: Int, m: Float, out: FloatArray) {
    val n = shard.normal
    val c = shard.centre
    val p = shard.corners[k]
    val push = m * (0.35f + 0.6f * shard.seed)
    val shrink = 1f - 0.5f * m
    val angle = m * (1.5f + 3f * shard.seed)
    val ca = cos(angle)
    val sa = sin(angle)
    val dx = (p.x - c.x) * shrink
    val dy = (p.y - c.y) * shrink
    val dz = (p.z - c.z) * shrink
    // Rodrigues: d cos + (n x d) sin + n (n . d)(1 - cos).
    val nd = (n.x * dx + n.y * dy + n.z * dz) * (1f - ca)
    out[0] = c.x + n.x * push + dx * ca + (n.y * dz - n.z * dy) * sa + n.x * nd
    out[1] = c.y + n.y * push + dy * ca + (n.z * dx - n.x * dz) * sa + n.y * nd
    out[2] = c.z + n.z * push + dz * ca + (n.x * dy - n.y * dx) * sa + n.z * nd
}

/** [bend] on floats, in place on `v[0..2]`, so the burst allocates nothing. */
internal fun bendInPlace(v: FloatArray, pull: Float) {
    val p = pull.coerceIn(-1f, 1f)
    val k = abs(p)
    val anchor = if (p >= 0f) -1f else 1.3f
    val squeeze = 1f - 0.024f * k
    v[0] *= squeeze
    v[1] = anchor + (v[1] - anchor) * (1f + 0.08f * k)
    v[2] *= squeeze
}

/**
 * Draws the burst as one triangle mesh, so the GPU fills every shard in a single
 * `drawVertices` call instead of 80 path fills. Slots are rewritten back to front each frame;
 * the index buffer never changes. Nothing here allocates per frame.
 */
internal class ShardRenderer {
    private val vertexCount = SHARD_COUNT * SHARD_VERTS + HANDLE_VERTS
    private val vertices = Vertices(
        VertexMode.Triangles,
        List(vertexCount) { Offset.Zero },
        List(vertexCount) { Offset.Zero },
        List(vertexCount) { Color.Transparent },
        indices(),
    )
    private val positions = vertices.positions
    private val colors = vertices.colors

    // White under Modulate leaves each vertex its own colour on Android and skiko alike.
    private val paint = Paint().apply { color = Color.White }

    private val screen = FloatArray(SHARD_COUNT * 8)
    private val depth = FloatArray(SHARD_COUNT)
    private val fill = IntArray(SHARD_COUNT)
    private val order = IntArray(SHARD_COUNT) { it }
    private val handle = FloatArray(HANDLE.size * 3)
    private val v = FloatArray(3)

    // The camera, set per frame.
    private var cy = 1f
    private var sy = 0f
    private var cp = 1f
    private var sp = 0f
    private var cr = 1f
    private var sr = 0f
    private var rx = 0f
    private var ry = 0f
    private var rz = 0f

    fun isSupported(): Boolean = shardsSupported()

    private fun aim(camera: BellCamera) {
        val d2r = (PI / 180.0).toFloat()
        cy = cos(camera.yaw * d2r); sy = sin(camera.yaw * d2r)
        cp = cos(camera.pitch * d2r); sp = sin(camera.pitch * d2r)
        cr = cos(camera.roll * d2r); sr = sin(camera.roll * d2r)
    }

    /** [BellCamera.rotate] into `rx, ry, rz`. */
    private fun rotate(x: Float, y: Float, z: Float) {
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val y2 = y * cp - z1 * sp
        rz = y * sp + z1 * cp
        rx = x1 * cr - y2 * sr
        ry = x1 * sr + y2 * cr
    }

    /** One frame at burst [m] in this scope's box, `scale` px per unit as [BellProjector]'s. */
    fun DrawScope.draw(camera: BellCamera, scale: Float, m: Float, pull: Float) {
        aim(camera)
        val halfW = size.width / 2f
        val halfH = size.height / 2f
        val mix = 0.25f + 0.6f * m
        for (k in 0 until SHARD_COUNT) {
            val shard = SHARD_TABLE[k]
            var sx = 0f
            var sy2 = 0f
            var sz = 0f
            for (j in 0 until 4) {
                shardCorner(shard, j, m, v)
                bendInPlace(v, pull)
                rotate(v[0], v[1], v[2])
                sx += rx; sy2 += ry; sz += rz
                val s = FOC / (CAMD - rz)
                screen[k * 8 + j * 2] = halfW + rx * s * scale
                screen[k * 8 + j * 2 + 1] = halfH - ry * s * scale
            }
            depth[k] = sz / 4f
            val n = shard.normal
            rotate(n.x, n.y, n.z)
            val front = rx * (-sx / 4f) + ry * (-sy2 / 4f) + rz * (CAMD - sz / 4f) > 0f
            val lit = if (front) 0.3f + 0.7f * max(0f, rx * LIGHT.x + ry * LIGHT.y + rz * LIGHT.z) else 0.2f
            val hue = BellPalette[shard.hue]
            fill[k] = argb(
                1f,
                lerp((30f + 50f * lit) / 255f, hue.red * lit, mix),
                lerp((22f + 40f * lit) / 255f, hue.green * lit, mix),
                lerp((44f + 70f * lit) / 255f, hue.blue * lit, mix),
            )
        }
        sortBackToFront()

        val edge = argb(0.2f + 0.6f * m, 1f, 200f / 255f, 1f)
        val halfEdge = EDGE_WIDTH.toPx() / 2f
        for (slot in 0 until SHARD_COUNT) {
            val k = order[slot]
            val base = slot * SHARD_VERTS
            for (j in 0 until 4) {
                put(base + j, screen[k * 8 + j * 2], screen[k * 8 + j * 2 + 1], fill[k])
            }
            for (e in 0 until 4) {
                val a = k * 8 + e * 2
                val b = k * 8 + ((e + 1) % 4) * 2
                edgeQuad(base + 4 + e * 4, screen[a], screen[a + 1], screen[b], screen[b + 1], halfEdge, edge)
            }
        }
        handleRibbon(scale, m, pull, halfW, halfH)
        drawIntoCanvas { it.drawVertices(vertices, BlendMode.Modulate, paint) }
    }

    /** Insertion sort on the last frame's order, which is nearly sorted already. */
    private fun sortBackToFront() {
        for (i in 1 until SHARD_COUNT) {
            val k = order[i]
            var j = i - 1
            while (j >= 0 && depth[order[j]] > depth[k]) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = k
        }
    }

    private fun put(i: Int, x: Float, y: Float, color: Int) {
        positions[i * 2] = x
        positions[i * 2 + 1] = y
        colors[i] = color
    }

    /** A stroke as a quad, squared off past both ends so neighbouring edges meet at corners. */
    private fun edgeQuad(i: Int, ax: Float, ay: Float, bx: Float, by: Float, half: Float, color: Int) {
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        val ux = if (len < 1e-3f) 0f else dx / len * half
        val uy = if (len < 1e-3f) 0f else dy / len * half
        put(i, ax - ux - uy, ay - uy + ux, color)
        put(i + 1, ax - ux + uy, ay - uy - ux, color)
        put(i + 2, bx + ux + uy, by + uy - ux, color)
        put(i + 3, bx + ux - uy, by + uy + ux, color)
    }

    /** The handle rises by `0.6 m` and fades, drawn last as the prototype does. */
    private fun handleRibbon(scale: Float, m: Float, pull: Float, halfW: Float, halfH: Float) {
        for (i in HANDLE.indices) {
            val h = HANDLE[i]
            v[0] = h.x; v[1] = h.y + HANDLE_LIFT * m; v[2] = h.z
            bendInPlace(v, pull)
            rotate(v[0], v[1], v[2])
            val s = FOC / (CAMD - rz)
            handle[i * 3] = halfW + rx * s * scale
            handle[i * 3 + 1] = halfH - ry * s * scale
            handle[i * 3 + 2] = s
        }
        val half = SHARD_HANDLE_WIDTH / 2f * scale * handle[(HANDLE.size / 2) * 3 + 2]
        val color = argb(1f - m, 185f / 255f, 168f / 255f, 1f)
        val base = SHARD_COUNT * SHARD_VERTS
        val last = HANDLE.size - 1
        for (i in HANDLE.indices) {
            val a = minOf(i + 1, last) * 3
            val b = maxOf(i - 1, 0) * 3
            val tx = handle[a] - handle[b]
            val ty = handle[a + 1] - handle[b + 1]
            val len = sqrt(tx * tx + ty * ty)
            val nx = if (len < 1e-3f) 0f else -ty / len * half
            val ny = if (len < 1e-3f) 0f else tx / len * half
            put(base + i * 2, handle[i * 3] + nx, handle[i * 3 + 1] + ny, color)
            put(base + i * 2 + 1, handle[i * 3] - nx, handle[i * 3 + 1] - ny, color)
        }
    }

    private companion object {
        val EDGE_WIDTH = 1.dp

        fun quad(out: MutableList<Int>, a: Int) {
            out += a; out += a + 1; out += a + 2
            out += a; out += a + 2; out += a + 3
        }

        /** Per slot: the fill, then its four edges over it. Then the handle's ribbon. */
        fun indices(): List<Int> {
            val out = ArrayList<Int>()
            for (slot in 0 until SHARD_COUNT) {
                val base = slot * SHARD_VERTS
                for (q in 0 until 5) quad(out, base + q * 4)
            }
            val base = SHARD_COUNT * SHARD_VERTS
            for (i in 0 until HANDLE.size - 1) {
                val l0 = base + i * 2
                out += l0; out += l0 + 1; out += l0 + 3
                out += l0; out += l0 + 3; out += l0 + 2
            }
            return out
        }

        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        fun argb(a: Float, r: Float, g: Float, b: Float): Int =
            (channel(a) shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

        fun channel(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}

/**
 * One frame of the shard morph: the bell bursts into shards that fly out, spin and shrink while
 * the camera swings, then re-form at the new view. The mesh fades over them at either end.
 */
internal fun DrawScope.drawShardMorph(
    transition: BellTransition,
    nowSeconds: Double,
    blobSeconds: Double,
    pull: Float,
    flash: Float,
    glow: Float,
    shards: ShardRenderer,
) {
    val m = shardAmount(transition.progress(nowSeconds))
    val swing = transition.camera(nowSeconds)
    val camera = swing.copy(zoom = swing.zoom * shardZoom(m))
    val padded = paddedMeshFrame(size, camera, pull) ?: return
    inset(padded.pad) {
        with(shards) { draw(camera, padded.frame.scale, m, pull) }
        val meshAlpha = shardMeshAlpha(m)
        if (meshAlpha > 0f) drawBellMesh(padded.frame, blobSeconds, flash, glow, alpha = meshAlpha)
    }
}
