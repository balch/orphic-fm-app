package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max

/** One projected face. `lit` is its brightness, 0..1; `isTop` marks the closed top quad. */
internal class MeshFace(
    val points: List<Offset>,
    val depth: Float,
    val outside: Boolean,
    val lit: Float,
    val isTop: Boolean,
)

internal sealed interface MeshItem {
    /** Mean view-space z; larger is nearer the camera. */
    val depth: Float

    class Face(val face: MeshFace) : MeshItem {
        override val depth: Float get() = face.depth
    }

    class Handle(val points: List<Projected>) : MeshItem {
        // `s = FOC / (CAMD - z)` inverts back to z.
        override val depth: Float = points.map { CAMD - FOC / it.s }.average().toFloat()
    }
}

/** A projected bell: items back to front, the 4 mouth corners, the bounds and the px scale. */
internal class MeshFrame(
    val items: List<MeshItem>,
    val rim: List<Offset>,
    val bounds: Rect,
    val scale: Float,
)

/** Projects the bell into `size`, or returns null when the box is under 1 px on a side. */
internal fun meshFrame(size: Size, camera: BellCamera, pull: Float = 0f): MeshFrame? {
    val projector = BellProjector(size, camera)
    if (projector.isEmpty) return null

    fun place(v: Vec3): Vec3 = camera.rotate(bend(v, pull))

    val cam = Vec3(0f, 0f, CAMD)
    val items = ArrayList<MeshItem>(FACES.size + 1)
    FACES.forEach { face ->
        val bent = face.map { bend(it, pull) }
        val normal = faceNormal(bent)
        val rotated = bent.map { camera.rotate(it) }
        val n = camera.rotate(normal)
        val centre = centroid(rotated)
        val outside = (n dot (cam - centre)) > 0f
        val lit = if (outside) 0.25f + 0.75f * max(0f, n dot LIGHT) else 0.18f
        val points = rotated.map { projector.project(it).let { p -> Offset(p.x, p.y) } }
        items += MeshItem.Face(MeshFace(points, rotated.map { it.z }.average().toFloat(), outside, lit, isTop = face === TOP))
    }
    items += MeshItem.Handle(HANDLE.map { projector.project(place(it)) })
    items.sortBy { it.depth }

    val rim = MOUTH.map { projector.project(place(it)).let { p -> Offset(p.x, p.y) } }
    val all = items.flatMap { item ->
        when (item) {
            is MeshItem.Face -> item.face.points
            is MeshItem.Handle -> item.points.map { Offset(it.x, it.y) }
        }
    }
    val bounds = Rect(
        left = all.minOf { it.x },
        top = all.minOf { it.y },
        right = all.maxOf { it.x },
        bottom = all.maxOf { it.y },
    )
    return MeshFrame(items, rim, bounds, projector.scale)
}

/**
 * [camera] zoomed so the resting bell's bounds reach [fraction] of [box] (centred in [size]) on
 * their tightest side. The zoom scales about the box centre, so each edge is fitted on its own.
 */
internal fun fittedCamera(size: Size, camera: BellCamera, fraction: Float, box: Size = size): BellCamera {
    val b = meshFrame(size, camera)?.bounds ?: return camera
    val cx = size.width / 2f
    val cy = size.height / 2f
    val halfW = box.width * fraction / 2f
    val halfH = box.height * fraction / 2f
    val k = minOf(
        halfW / max(cx - b.left, 1e-3f),
        halfW / max(b.right - cx, 1e-3f),
        halfH / max(cy - b.top, 1e-3f),
        halfH / max(b.bottom - cy, 1e-3f),
    )
    return if (k.isFinite() && k > 0f) camera.copy(zoom = camera.zoom * k) else camera
}

internal fun centroid(points: List<Vec3>): Vec3 =
    points.fold(Vec3(0f, 0f, 0f)) { acc, v -> acc + v } * (1f / points.size)

/** The face normal, flipped to point away from the body centroid at the origin. */
internal fun faceNormal(face: List<Vec3>): Vec3 {
    val n = ((face[1] - face[0]) cross (face[2] - face[0])).normalized()
    return if ((n dot centroid(face)) < 0f) n * -1f else n
}
