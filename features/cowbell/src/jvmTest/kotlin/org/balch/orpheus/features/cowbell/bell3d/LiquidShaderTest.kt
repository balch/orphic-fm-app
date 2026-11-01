package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiquidShaderTest {

    @Test
    fun `liquid sksl compiles`() {
        RuntimeEffect.makeForShader(LIQUID_SKSL).close()
    }

    @Test
    fun `every uniform is settable`() {
        val effect = RuntimeEffect.makeForShader(LIQUID_SKSL)
        val builder = RuntimeShaderBuilder(effect)
        builder.uniform("t", 1f)
        builder.uniform("np", 4)
        builder.uniform("poly", FloatArray(32))
        builder.uniform("drops", FloatArray(32))
        builder.uniform("k", 24f)
        builder.uniform("bevel", 30f)
        builder.uniform("inflate", 10f)
        builder.uniform("unit", 1f)
        builder.makeShader().close()
        effect.close()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the renderer gives a brush mid morph`() {
        val width = 200
        val height = 300
        val camera = BellCamera.forMode(CowbellMode.TAP)
        val projector = BellProjector(Size(width.toFloat(), height.toFloat()), camera)
        val renderer = LiquidRenderer()
        assertTrue(renderer.isSupported(), "the SkSL should compile on the raster backend")
        val frame = liquidFrame(projector, camera, 0.4f, 1f)
        val brush = renderer.brush(projector.scale / PROTOTYPE_PX_PER_UNIT, 1f, frame)
        assertNotNull(brush)

        val scene = ImageComposeScene(width, height, Density(1f)) {
            Canvas(Modifier.fillMaxSize()) { drawRect(brush) }
        }
        val pixels = try {
            scene.render().toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
            renderer.dispose()
        }

        var covered = 0
        for (y in 0 until height) for (x in 0 until width) if (pixels[x, y].alpha > 0.5f) covered++
        val coverage = covered.toFloat() / (width * height)
        println("[liquid] coverage ${"%.3f".format(coverage)} ($covered px), corner alpha ${pixels[0, 0].alpha}")
        // It measured 11.9%: the bounds pin the pool's scale as well as its presence.
        assertTrue(coverage in 0.05f..0.4f, "liquid covers ${coverage * 100}% of the pixels")
        assertTrue(pixels[0, 0].alpha < 0.1f, "corner alpha ${pixels[0, 0].alpha}")
    }
}
