package org.balch.orpheus.features.pulsar

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class PulsarStaircaseTest {
    private val width = 200
    private val height = 60
    private val xStep = 20f

    // Repeats, a one-step blip, rises and falls: every way a run can start and end.
    private val ys = floatArrayOf(40f, 40f, 20f, 20f, 50f, 10f, 10f, 30f, 30f, 30f)

    @Test fun `rects cover the same pixels as the stroked staircase they replace`() {
        for (strokeWidth in listOf(2f, 4f)) {
            val rects = render { drawStaircase(ys, ys.size, xStep, width.toFloat(), strokeWidth, Color.White) }
            val stroked = render { drawPath(staircasePath(), Color.White, style = Stroke(strokeWidth)) }
            var worst = 0f
            for (x in 0 until width) for (y in 0 until height) {
                worst = maxOf(worst, abs(rects[x, y].alpha - stroked[x, y].alpha))
            }
            assertTrue(worst < 0.01f, "stroke $strokeWidth: worst alpha difference $worst")
        }
    }

    // The path PulsarStepGrid stroked before: hold each level, then jump at the next step.
    private fun staircasePath() = Path().apply {
        moveTo(0f, ys[0])
        for (i in 1 until ys.size) {
            lineTo(i * xStep, ys[i - 1])
            lineTo(i * xStep, ys[i])
        }
        lineTo(width.toFloat(), ys.last())
    }

    private fun render(block: DrawScope.() -> Unit) = ImageBitmap(width, height).also {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(width.toFloat(), height.toFloat()), block)
    }.toPixelMap()
}
