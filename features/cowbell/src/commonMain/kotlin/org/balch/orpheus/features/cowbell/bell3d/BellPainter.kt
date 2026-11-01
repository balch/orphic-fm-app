package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal val BellPalette: List<Color> = listOf(
    OrpheusColors.neonMagenta,
    OrpheusColors.neonCyan,
    OrpheusColors.cosmicPurple,
    OrpheusColors.neonOrange,
    OrpheusColors.synthGreen,
    OrpheusColors.electricBlue,
)

/** The handle's dark stroke and its light core, in model units scaled by the midpoint's `s`. */
internal const val HANDLE_OUTER_WIDTH = 0.104f
internal const val HANDLE_CORE_WIDTH = 0.032f

private val RIM_WIDE: Dp = 9.dp

/** Half the widest rim stroke, at full glow: `9dp * (0.5 + 1) / 2`. */
internal val RIM_WIDE_MAX_HALF_DP: Dp = RIM_WIDE * 0.75f

private val HandleDark = Color(0xFF2A2440)
private val HandleCore = Color(185, 168, 255).copy(alpha = 0.55f)
private val InsideEdge = Color(120, 90, 200).copy(alpha = 0.35f)

internal data class BlobSpot(val center: Offset, val radius: Float)

/** The six colour blobs drifting under the faces. The trig runs in Double so hours of uptime stay smooth. */
internal class BlobField {
    fun blob(i: Int, timeSeconds: Double, flash: Float, size: Size, scale: Float): BlobSpot {
        val t = timeSeconds
        val x = size.width / 2.0 + 0.367 * size.width * sin(t * (0.3 + 0.07 * i) + i)
        val y = size.height / 2.0 + 0.333 * size.height * cos(t * (0.4 + 0.05 * i) + 2.0 * i)
        val radius = (0.68 + 0.32 * sin(t + i) + 0.56 * flash) * scale
        return BlobSpot(Offset(x.toFloat(), y.toFloat()), radius.toFloat())
    }
}

private val Blobs = BlobField()

/** A frame fitted into its box less [pad] on every side; paint it under `inset(pad)`. */
internal class PaddedFrame(val frame: MeshFrame, val pad: Float) {
    val offset: Offset get() = Offset(pad, pad)
    val bounds: Rect get() = frame.bounds.translate(offset)
}

/** The largest perspective scale any handle point can reach, at full pull either way. */
private val HANDLE_MAX_S: Float = run {
    val reach = listOf(-1f, 1f).maxOf { p -> HANDLE.maxOf { v -> bend(v, p).let { sqrt(it dot it) } } }
    FOC / (CAMD - reach)
}

/**
 * Strokes are not in the mesh bounds, so the mesh fits a box inset by the widest stroke's half
 * width: the full-glow rim, or the handle at its nearest. Joins are round so corners stay in.
 */
internal fun Density.paddedMeshFrame(size: Size, camera: BellCamera, pull: Float = 0f): PaddedFrame? {
    val handleHalf = HANDLE_OUTER_WIDTH / 2f * BellProjector(size, camera).scale * HANDLE_MAX_S
    val pad = max(RIM_WIDE_MAX_HALF_DP.toPx(), handleHalf)
    val frame = meshFrame(Size(size.width - 2f * pad, size.height - 2f * pad), camera, pull) ?: return null
    return PaddedFrame(frame, pad)
}

/** Paints [frame] back to front, then [belowRim], then the mouth rim. [glow] lights the rim; [flash] the faces. */
internal fun DrawScope.drawBellMesh(
    frame: MeshFrame,
    timeSeconds: Double,
    flash: Float,
    glow: Float,
    alpha: Float = 1f,
    belowRim: (DrawScope.() -> Unit)? = null,
) {
    if (alpha <= 0.01f) return
    val f = flash.coerceIn(0f, 1f)
    // One brush per blob for the whole frame; each face sets its strength through the draw alpha.
    val blobs = List(BellPalette.size) { i ->
        val spot = Blobs.blob(i, timeSeconds, f, size, frame.scale)
        val c = BellPalette[i]
        spot to Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), spot.center, spot.radius)
    }
    val outsideEdge = Color(255, (130 + 100 * f).toInt(), 240).copy(alpha = 0.35f + 0.5f * f)
    val outsideStroke = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round)
    val insideStroke = Stroke(1.dp.toPx(), join = StrokeJoin.Round)
    for (item in frame.items) {
        when (item) {
            is MeshItem.Handle -> drawHandle(item.points, frame.scale, alpha)
            is MeshItem.Face -> {
                val face = item.face
                val path = closedPath(face.points)
                val lit = face.lit
                drawPath(path, Color((22 + 40 * lit) / 255f, (16 + 30 * lit) / 255f, (34 + 60 * lit) / 255f), alpha = alpha)
                val shade = if (face.outside) 0.4f + lit else 0.35f
                val a = min(1f, (0.25f + 0.5f * f) * shade)
                clipPath(path) {
                    for ((spot, brush) in blobs) {
                        drawCircle(brush, spot.radius, spot.center, alpha = a * alpha, blendMode = BlendMode.Plus)
                    }
                }
                if (face.outside) {
                    drawPath(path, outsideEdge, alpha = alpha, style = outsideStroke)
                } else {
                    drawPath(path, InsideEdge, alpha = alpha, style = insideStroke)
                }
            }
        }
    }
    belowRim?.invoke(this)
    drawRim(frame.rim, glow.coerceIn(0f, 1f), alpha)
}

private fun DrawScope.drawHandle(points: List<Projected>, scale: Float, alpha: Float) {
    val s = points[points.size / 2].s
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, HandleDark, alpha = alpha, style = Stroke(HANDLE_OUTER_WIDTH * scale * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, HandleCore, alpha = alpha, style = Stroke(HANDLE_CORE_WIDTH * scale * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A soft glow in three strokes, wide to fine. */
private fun DrawScope.drawRim(rim: List<Offset>, glow: Float, alpha: Float) {
    val lit = 0.2f + 0.8f * glow
    val color = lerp(OrpheusColors.neonMagenta, Color.White, 0.4f * glow)
    val path = closedPath(rim)
    val wide = RIM_WIDE.toPx() * (0.5f + lit)
    drawPath(path, color.copy(alpha = 0.12f * lit), alpha = alpha, style = Stroke(wide, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = 0.28f * lit), alpha = alpha, style = Stroke(wide / 2f, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = 0.5f + 0.5f * lit), alpha = alpha, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
}

private fun closedPath(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    close()
}
