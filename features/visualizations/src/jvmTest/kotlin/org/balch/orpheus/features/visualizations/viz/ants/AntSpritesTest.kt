package org.balch.orpheus.features.visualizations.viz.ants

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.balch.orpheus.features.visualizations.viz.sprite.drawSprites
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class AntSpritesTest {
    private val voiceColors = arrayOf(Color(0xFF00FFD5))
    private val background = Color(0xFF080810)

    private fun render(size: Int, draw: DrawScope.() -> Unit): ImageBitmap {
        val image = ImageBitmap(size, size)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(size.toFloat(), size.toFloat())) {
            drawRect(background)
            draw()
        }
        return image
    }

    private fun ants(heading: Float, x: Float = 0.5f, z: Float = 0.5f) =
        arrayOf(Ant(x = x, z = z, headingAngle = heading, colorIndex = 1, active = true))

    /** Green over background: this colour's green is full, so it tracks how much ant covers a pixel. */
    private fun coverage(image: ImageBitmap): Array<FloatArray> {
        val pixels = image.toPixelMap()
        return Array(image.height) { y -> FloatArray(image.width) { x -> pixels[x, y].green - background.green } }
    }

    /** Averages over [radius] so a line's jaggies compare with its antialiased original by how much ink lands nearby. */
    private fun blur(map: Array<FloatArray>, radius: Int): Array<FloatArray> {
        val n = map.size
        return Array(n) { y ->
            FloatArray(n) { x ->
                var sum = 0f
                for (j in -radius..radius) for (i in -radius..radius) {
                    sum += map[(y + j).coerceIn(0, n - 1)][(x + i).coerceIn(0, n - 1)]
                }
                sum / ((2 * radius + 1) * (2 * radius + 1))
            }
        }
    }

    private fun comparePaths(heading: Float, x: Float = 0.5f, z: Float = 0.5f) {
        val size = 400
        val batch = antSpriteBatch()
        val ants = ants(heading, x, z)
        val canvas = coverage(render(size) { drawAnts(ants, voiceColors, Stroke(width = 1f)) })
        val sprites = coverage(render(size) { drawAntSprites(ants, voiceColors, batch) })

        val canvasTotal = canvas.sumOf { row -> row.sumOf { it.toDouble() } }
        val spritesTotal = sprites.sumOf { row -> row.sumOf { it.toDouble() } }
        assertTrue(canvasTotal > 100.0, "the ant drew, total $canvasTotal")
        assertTrue(abs(spritesTotal / canvasTotal - 1.0) < 0.05, "total ink $spritesTotal vs canvas $canvasTotal")

        val blurredCanvas = blur(canvas, 1)
        val blurredSprites = blur(sprites, 1)
        var worst = 0f
        for (j in 0 until size) for (i in 0 until size) {
            worst = maxOf(worst, abs(blurredCanvas[j][i] - blurredSprites[j][i]))
        }
        // The one ring cell runs a little wide at the abdomen's radius, leaving about 0.19 on that rim.
        assertTrue(worst < 0.2f, "worst local coverage difference was $worst at heading $heading")
    }

    @Test fun `one ant covers the same pixels through both paths at every heading`() {
        for (i in 0 until 16) comparePaths(heading = i * (2f * PI.toFloat() / 16f))
    }

    @Test fun `an ant off a pixel centre still matches`() {
        comparePaths(heading = 0.7f, x = 0.413f, z = 0.587f)
    }

    @Test fun `a thin line quad is opaque along its whole length`() {
        val pixels = render(64) { drawLineQuad(24f, 1f, 0f) }.toPixelMap()
        // 0.5 half-width straddles one row of pixel centres, whichever row that is.
        for (x in 9..54) {
            val best = maxOf(pixels[x, 31].red, pixels[x, 32].red)
            assertTrue(best > 0.98f, "line at x=$x came out faded, $best")
        }
    }

    @Test fun `a thin diagonal line has no gaps`() {
        val pixels = render(64) { drawLineQuad(24f, 0.8f, 0.6f) }.toPixelMap()
        for (x in 16..48) {
            val brightest = (0 until 64).maxOf { y -> pixels[x, y].red }
            assertTrue(brightest > 0.98f, "no opaque pixel in column $x, brightest $brightest")
        }
    }

    private fun DrawScope.drawLineQuad(halfLength: Float, cos: Float, sin: Float) {
        drawSprites(antSpriteBatch(), BlendMode.SrcOver) {
            quad(ANT_CELL_LINE, 32f, 32f, halfLength, 0.5f, cos, sin, Color.White)
        }
    }
}
