package org.balch.orpheus.features.visualizations.viz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class BlackHoleSunParticlesTest {
    private val size = 512
    private val scale = size / 2f
    private val eventHorizon = 0.31f

    private fun particle(id: Int, type: AccretionParticleType, life: Float): AccretionParticle {
        val angle = id * 0.37f
        val radius = 0.2f + (id % 11) * 0.05f
        return AccretionParticle(
            id = id,
            x = cos(angle) * radius,
            y = sin(angle) * radius,
            vx = 0f,
            vy = 0f,
            angularVelocity = 0f,
            color = listOf(Color.Magenta, Color.Blue, Color.Green, Color.Cyan)[id % 4].copy(alpha = 0.9f),
            size = 4f + (id % 14),
            life = life,
            decay = 0f,
            type = type,
            distanceFromCenter = radius,
        )
    }

    private fun render(draw: DrawScope.() -> Unit): ImageBitmap {
        val image = ImageBitmap(size, size)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(image),
            Size(size.toFloat(), size.toFloat()),
        ) {
            drawRect(Color.Black)
            draw()
        }
        return image
    }

    private fun assertBatchMatchesCircles(particles: List<AccretionParticle>, worstAllowed: Float) {
        val time = 3.7f
        val c = size / 2f
        val batched = render {
            drawParticleSprites(particleSpriteBatch(), particles, time, c, c, scale, eventHorizon)
        }.toPixelMap()
        val circles = render {
            drawParticleCircles(particles, time, c, c, scale, eventHorizon)
        }.toPixelMap()

        var sum = 0f
        var worst = 0f
        var lit = 0
        var batchedEnergy = 0f
        var circlesEnergy = 0f
        for (y in 0 until size) for (x in 0 until size) {
            val a = batched[x, y]
            val b = circles[x, y]
            val diff = max(max(abs(a.red - b.red), abs(a.green - b.green)), abs(a.blue - b.blue))
            sum += diff
            worst = max(worst, diff)
            batchedEnergy += a.red + a.green + a.blue
            circlesEnergy += b.red + b.green + b.blue
            if (b.red + b.green + b.blue > 0.02f) lit++
        }
        val mean = sum / (size * size)
        val energy = batchedEnergy / circlesEnergy
        assertTrue(lit > 100, "the scene should actually light up, lit=$lit")
        assertTrue(mean < 0.02f, "mean channel difference was $mean")
        assertTrue(energy in 0.95f..1.06f, "batched over circles total light was $energy")
        assertTrue(worst < worstAllowed, "worst channel difference was $worst")
    }

    // The bright dot's edge is the largest gap: a few pixels of aliased rim, not antialiased.
    @Test fun `a lone cloud of each type matches the circles`() {
        assertBatchMatchesCircles(listOf(particle(9, AccretionParticleType.EMITTED, 0.8f)), worstAllowed = 0.12f)
        assertBatchMatchesCircles(listOf(particle(9, AccretionParticleType.ORBITING, 0.8f)), worstAllowed = 0.05f)
        assertBatchMatchesCircles(listOf(particle(9, AccretionParticleType.ABSORBED, 0.8f)), worstAllowed = 0.05f)
    }

    // Over 1024 sprites, so this also crosses a mesh flush.
    @Test fun `a dense mixed scene matches the circles`() {
        val types = AccretionParticleType.entries
        val particles = List(400) { i -> particle(i, types[i % 3], 0.2f + (i % 9) * 0.1f) }
        assertBatchMatchesCircles(particles, worstAllowed = 0.2f)
    }
}
