package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader

/** Measured off the keyframes, normalized to the square image. They agree to about 0.03. */
object FaceLandmarks {
    val leftEye = Offset(0.36f, 0.44f)
    val rightEye = Offset(0.65f, 0.44f)
    val nose = Offset(0.50f, 0.58f)
    val mouth = Offset(0.50f, 0.75f)
    val center = Offset(0.50f, 0.55f)
}

/**
 * Which parts of the face turn first: white early, black last. Eyes lead, then the mouth and
 * nose, then the rest of the face, with the scalp and background last.
 */
fun buildFaceBiasMap(size: Int = 256): ImageBitmap = biasMap(size) {
    blob(FaceLandmarks.center, 0.55f, 0.30f)
    blob(FaceLandmarks.nose, 0.12f, 0.60f)
    blob(FaceLandmarks.mouth, 0.20f, 0.75f)
    blob(FaceLandmarks.leftEye, 0.15f, 1.00f)
    blob(FaceLandmarks.rightEye, 0.15f, 1.00f)
}

/**
 * The final pair turns the other way round: the jaw and nose go to bone first and the eyes are
 * held back to the very end, so a turn stopped at [LAST_STAGE_CAP] is a skull that still looks
 * at you rather than a pair of empty sockets.
 */
fun buildLastStageBiasMap(size: Int = 256): ImageBitmap = biasMap(size) {
    blob(FaceLandmarks.center, 0.55f, 0.30f)
    blob(FaceLandmarks.nose, 0.14f, 0.85f)
    blob(FaceLandmarks.mouth, 0.24f, 1.00f)
    shield(FaceLandmarks.leftEye, 0.17f)
    shield(FaceLandmarks.rightEye, 0.17f)
}

private class BiasPainter(private val canvas: Canvas, private val s: Float) {
    fun blob(at: Offset, radius: Float, peak: Float) {
        val c = Offset(at.x * s, at.y * s)
        val paint = Paint().apply {
            shader = RadialGradientShader(c, radius * s, listOf(Color(peak, peak, peak), Color.Black))
            // Lighten keeps the strongest region per pixel instead of summing past white.
            blendMode = BlendMode.Lighten
        }
        canvas.drawCircle(c, radius * s, paint)
    }

    /** Paints black over whatever is there, feathered, so the region turns last. Draw it last. */
    fun shield(at: Offset, radius: Float) {
        val c = Offset(at.x * s, at.y * s)
        val paint = Paint().apply {
            shader = RadialGradientShader(
                c, radius * s,
                listOf(Color.Black, Color.Black, Color.Transparent),
                listOf(0f, 0.6f, 1f),
            )
        }
        canvas.drawCircle(c, radius * s, paint)
    }
}

private fun biasMap(size: Int, paint: BiasPainter.() -> Unit): ImageBitmap {
    val bitmap = ImageBitmap(size, size)
    val canvas = Canvas(bitmap)
    val s = size.toFloat()
    canvas.drawRect(Rect(0f, 0f, s, s), Paint().apply { color = Color.Black })
    BiasPainter(canvas, s).paint()
    return bitmap
}
