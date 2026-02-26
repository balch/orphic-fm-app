package org.balch.orpheus.features.mediapipe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.gestures.KeyboardInteractionEngine.Companion.KEY_X_MAX
import org.balch.orpheus.core.gestures.KeyboardInteractionEngine.Companion.KEY_X_MIN
import org.balch.orpheus.core.gestures.KeyboardInteractionEngine.Companion.NUM_KEYS
import org.balch.orpheus.core.gestures.KeyboardInteractionEngine.Companion.PERSPECTIVE_RATIO
import org.balch.orpheus.core.gestures.KeyboardInteractionEngine.Companion.ZONE_Y_ENTER
import org.balch.orpheus.core.gestures.KeyboardInteractionEngine.Companion.ZONE_Y_MAX

// Chromatic octave C through B — true for sharps/flats
private val IS_BLACK_KEY = booleanArrayOf(
    //  C      C#     D      D#     E      F      F#     G      G#     A      A#     B
    false, true, false, true, false, false, true, false, true, false, true, false,
)

private val NOTE_NAMES = arrayOf(
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
)

private const val BLACK_KEY_HEIGHT_RATIO = 0.55f

private val QUAD_COLORS = arrayOf(
    Color(0xFFFF00FF), // magenta – keys 0-3
    Color(0xFF00FF88), // green   – keys 4-7
    Color(0xFF00DDFF), // cyan    – keys 8-11
)

/**
 * Draws a piano-style keyboard overlay with 3D perspective.
 *
 * Keys are drawn as trapezoids — narrower at the top (far edge) and wider
 * at the bottom (near edge) — creating the illusion of a keyboard receding
 * into the distance. A vertical gradient from dark to light reinforces depth.
 */
@Composable
fun KeyboardOverlay(
    pressedKeys: Set<Int>,
    engineName: String?,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Zone boundaries (raised from original to clear the breadcrumb bar)
        val zoneTop = ZONE_Y_ENTER * h
        val zoneBottom = ZONE_Y_MAX * h
        val zoneHeight = zoneBottom - zoneTop

        // Bottom edge (near/wide) uses the full KEY_X range
        val bottomLeft = KEY_X_MIN * w
        val bottomRight = KEY_X_MAX * w
        val bottomWidth = bottomRight - bottomLeft

        // Top edge (far/narrow) is centered and scaled down
        val topWidth = bottomWidth * PERSPECTIVE_RATIO
        val topLeft = bottomLeft + (bottomWidth - topWidth) / 2f
        val topRight = topLeft + topWidth

        val keyWidthBottom = bottomWidth / NUM_KEYS
        val keyWidthTop = topWidth / NUM_KEYS

        // Subtle shadow under the keyboard for grounding
        val shadowPath = Path().apply {
            moveTo(topLeft - 4f, zoneTop - 2f)
            lineTo(topRight + 4f, zoneTop - 2f)
            lineTo(bottomRight + 6f, zoneBottom + 4f)
            lineTo(bottomLeft - 6f, zoneBottom + 4f)
            close()
        }
        drawPath(shadowPath, Color.Black.copy(alpha = 0.3f))

        // --- Draw natural (white) keys first ---
        for (i in 0 until NUM_KEYS) {
            if (IS_BLACK_KEY[i]) continue

            val isPressed = i in pressedKeys
            val fillColor = if (isPressed) {
                QUAD_COLORS[i / 4].copy(alpha = 0.6f)
            } else {
                null // gradient fill below
            }

            drawPerspectiveKey(
                index = i,
                topLeft = topLeft,
                bottomLeft = bottomLeft,
                keyWidthTop = keyWidthTop,
                keyWidthBottom = keyWidthBottom,
                yTop = zoneTop,
                yBottom = zoneBottom,
                fillColor = fillColor,
                gradientTop = Color.White.copy(alpha = 0.08f),
                gradientBottom = Color.White.copy(alpha = 0.25f),
                borderColor = Color.White.copy(alpha = 0.5f),
                borderWidth = 1.5f,
            )

            // Note label near bottom of key
            val label = NOTE_NAMES[i]
            val textResult = textMeasurer.measure(
                text = label,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                ),
            )
            val keyCenterX = bottomLeft + i * keyWidthBottom + keyWidthBottom / 2f
            drawText(
                textLayoutResult = textResult,
                topLeft = Offset(
                    x = keyCenterX - textResult.size.width / 2f,
                    y = zoneBottom - textResult.size.height - 6f,
                ),
            )
        }

        // --- Draw black keys on top ---
        val blackZoneHeight = zoneHeight * BLACK_KEY_HEIGHT_RATIO
        val blackYBottom = zoneTop + blackZoneHeight

        // The perspective ratio at the black key bottom edge (interpolated)
        val blackFraction = BLACK_KEY_HEIGHT_RATIO
        val blackBottomWidth = topWidth + (bottomWidth - topWidth) * blackFraction
        val blackBottomLeft = bottomLeft + (bottomWidth - blackBottomWidth) / 2f
        val blackKeyWidthBottom = blackBottomWidth / NUM_KEYS

        for (i in 0 until NUM_KEYS) {
            if (!IS_BLACK_KEY[i]) continue

            val isPressed = i in pressedKeys
            val fillColor = if (isPressed) {
                QUAD_COLORS[i / 4].copy(alpha = 0.75f)
            } else {
                null
            }

            drawPerspectiveKey(
                index = i,
                topLeft = topLeft,
                bottomLeft = blackBottomLeft,
                keyWidthTop = keyWidthTop,
                keyWidthBottom = blackKeyWidthBottom,
                yTop = zoneTop,
                yBottom = blackYBottom,
                fillColor = fillColor,
                gradientTop = Color(0xFF1A1A1A).copy(alpha = 0.7f),
                gradientBottom = Color(0xFF333333).copy(alpha = 0.8f),
                borderColor = Color.White.copy(alpha = 0.35f),
                borderWidth = 1f,
            )
        }

        // --- Engine name label (top-center above keyboard) ---
        if (engineName != null) {
            val engineTextResult = textMeasurer.measure(
                text = engineName,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                ),
            )
            val centerX = (topLeft + topRight) / 2f
            drawText(
                textLayoutResult = engineTextResult,
                topLeft = Offset(
                    x = centerX - engineTextResult.size.width / 2f,
                    y = zoneTop - engineTextResult.size.height - 4f,
                ),
            )
        }
    }
}

/**
 * Draw a single key as a trapezoid with perspective.
 * The key narrows from [keyWidthBottom] at yBottom to [keyWidthTop] at yTop.
 */
private fun DrawScope.drawPerspectiveKey(
    index: Int,
    topLeft: Float,
    bottomLeft: Float,
    keyWidthTop: Float,
    keyWidthBottom: Float,
    yTop: Float,
    yBottom: Float,
    fillColor: Color?,
    gradientTop: Color,
    gradientBottom: Color,
    borderColor: Color,
    borderWidth: Float,
) {
    val tl = Offset(topLeft + index * keyWidthTop, yTop)
    val tr = Offset(topLeft + (index + 1) * keyWidthTop, yTop)
    val br = Offset(bottomLeft + (index + 1) * keyWidthBottom, yBottom)
    val bl = Offset(bottomLeft + index * keyWidthBottom, yBottom)

    val path = Path().apply {
        moveTo(tl.x, tl.y)
        lineTo(tr.x, tr.y)
        lineTo(br.x, br.y)
        lineTo(bl.x, bl.y)
        close()
    }

    // Fill: solid color when pressed, vertical gradient when unpressed
    if (fillColor != null) {
        drawPath(path, fillColor, style = Fill)
    } else {
        drawPath(
            path,
            brush = Brush.verticalGradient(
                colors = listOf(gradientTop, gradientBottom),
                startY = yTop,
                endY = yBottom,
            ),
            style = Fill,
        )
    }

    // Border
    drawPath(path, borderColor, style = Stroke(width = borderWidth))
}
