package org.balch.orpheus.features.visualizations.viz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.balch.orpheus.features.visualizations.viz.sprite.SOLID_DISC
import org.balch.orpheus.features.visualizations.viz.sprite.SpriteBatch
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class FireworksParticlesTest {
    private fun render(particles: List<Particle>, useSprites: Boolean, canvasSize: Int, knob: Float = 1f): PixelMap {
        val image = ImageBitmap(canvasSize, canvasSize)
        val sprites = if (useSprites) SpriteBatch(listOf(SOLID_DISC)) else null
        val size = Size(canvasSize.toFloat(), canvasSize.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), size) {
            drawRect(Color.Black)
            drawParticles(particles, sprites, knob)
        }
        return image.toPixelMap()
    }

    private fun particle(
        x: Float,
        y: Float,
        size: Float,
        life: Float,
        color: Color,
        type: ParticleType = ParticleType.SPARK,
    ) = Particle(x, y, 0f, 0f, color, size, life, 0f, type)

    private fun PixelMap.light(): Float {
        var sum = 0f
        for (y in 0 until height) for (x in 0 until width) {
            val c = this[x, y]
            sum += c.red + c.green + c.blue
        }
        return sum
    }

    // Scattered off the pixel grid, the way a simulation places them.
    private fun field(count: Int, sizes: ClosedFloatingPointRange<Float>, life: Float): List<Particle> {
        val random = Random(7)
        return List(count) {
            particle(
                x = 100f + random.nextFloat() * 800f,
                y = 100f + random.nextFloat() * 800f,
                size = sizes.start + random.nextFloat() * (sizes.endInclusive - sizes.start),
                life = life,
                color = Color(0xFF00E5FF).copy(alpha = 0.1f),
            )
        }
    }

    private fun assertSameLight(particles: List<Particle>, canvasSize: Int, tolerance: Float) {
        val circles = render(particles, useSprites = false, canvasSize).light()
        val batched = render(particles, useSprites = true, canvasSize).light()
        val drift = abs(batched - circles) / circles
        assertTrue(drift < tolerance, "sprites $batched against circles $circles, off by $drift")
    }

    @Test fun `a typical spark field gives the same light on both paths`() {
        assertSameLight(field(1500, 1f..4f, life = 1f), canvasSize = 1000, tolerance = 0.1f)
    }

    @Test fun `a fading spark field gives the same light on both paths`() {
        assertSameLight(field(1500, 1f..4f, life = 0.3f), canvasSize = 1000, tolerance = 0.1f)
    }

    @Test fun `a high density spark field gives the same light on both paths`() {
        assertSameLight(field(1500, 1f..4f, life = 1f), canvasSize = 2400, tolerance = 0.1f)
    }

    @Test fun `a small canvas spark field gives the same light on both paths`() {
        assertSameLight(field(1500, 1f..4f, life = 1f), canvasSize = 500, tolerance = 0.1f)
    }

    @Test fun `a core flash matches the circle at its centre and past its rim`() {
        val core = listOf(particle(500f, 500f, 30f, life = 1f, color = Color.White.copy(alpha = 0.6f)))
        val circles = render(core, useSprites = false, canvasSize = 200)
        val batched = render(core, useSprites = true, canvasSize = 200)
        assertTrue(abs(circles[100, 100].red - batched[100, 100].red) < 0.02f, "centre ${batched[100, 100]}")
        assertTrue(batched[100, 105].red > 0.5f, "inside the radius was ${batched[100, 105]}")
        assertTrue(batched[100, 107].red < 0.02f, "past the radius was ${batched[100, 107]}")
    }

    @Test fun `a rocket head lands over a spark even when the spark comes later in the list`() {
        val particles = listOf(
            particle(500f, 500f, 4f, life = 1f, color = Color.White, type = ParticleType.ROCKET),
            particle(500f, 500f, 12f, life = 1f, color = Color.Red.copy(alpha = 0.5f)),
        )
        val centre = render(particles, useSprites = true, canvasSize = 1000, knob = 0.5f)[500, 500]
        // Spark then half-white gives 0.75 red. List order, which the fallback keeps, adds up to full red.
        assertTrue(centre.red in 0.7f..0.8f, "centre was $centre")
        val listOrder = render(particles, useSprites = false, canvasSize = 1000, knob = 0.5f)[500, 500]
        assertTrue(listOrder.red > 0.95f, "list order centre was $listOrder")
    }
}
