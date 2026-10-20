package org.balch.orpheus.features.visualizations.viz.shader

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Surface
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwirlyShaderTest {
    private val size = 512
    private val maxR = size * 0.7f

    // A bad shader is otherwise only visible as an empty viz at runtime.
    @Test fun `the shader compiles`() {
        RuntimeEffect.makeForShader(SwirlyShaderSource.SKSL).close()
    }

    /**
     * Two turns, time 0: along the ray to the right, arm i crosses at t = k/2 - i/12, so windings
     * sit 0.95R/12 apart (~28 px), wider than an 8 px arm's 20 px glow.
     */
    @Test fun `arms land on the spiral and leave the gaps between windings clear`() {
        val alpha = render(sweep = 2f * 2f * PI.toFloat())
        fun alphaAtRadius(r: Float) = alpha(size / 2 + r.toInt(), size / 2)

        // Arm 0 at t = 0.5.
        assertTrue(alphaAtRadius(maxR * 0.525f) > 250, "on arm 0: ${alphaAtRadius(maxR * 0.525f)}")
        // Halfway to arm 1's winding at t = 5/12.
        val between = (0.525f + (1f - 5f / 12f * 0.95f)) / 2f
        assertEquals(0, alphaAtRadius(maxR * between), "between windings")
    }

    private fun render(sweep: Float): (Int, Int) -> Int {
        val effect = RuntimeEffect.makeForShader(SwirlyShaderSource.SKSL)
        val builder = RuntimeShaderBuilder(effect).apply {
            uniform("resolution", size.toFloat(), size.toFloat())
            uniform("time", 0f)
            uniform("lfo", 0f)
            uniform("sweep", sweep)
            uniform("armColor", FloatArray(SWIRLY_ARM_COUNT * 4) { if (it % 4 == 1) 0f else 1f })
            uniform("armWidth", FloatArray(SWIRLY_ARM_COUNT) { 8f })
        }
        val surface = Surface.makeRasterN32Premul(size, size)
        surface.canvas.drawPaint(Paint().apply { shader = builder.makeShader() })
        val bitmap = Bitmap.makeFromImage(surface.makeImageSnapshot())
        return { x, y -> Color.getA(bitmap.getColor(x, y)) }
    }
}
