package org.balch.orpheus.features.visualizations.viz.ants

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.visualizations.viz.shader.LayerShader
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaShader
import org.balch.orpheus.ui.theme.OrpheusColors
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** The sprite layer against the CPU field it replaces, rendered through Skia's raster backend. */
class PodLayerTest {
    private val voiceColors = arrayOf(
        OrpheusColors.antNeonMagenta, OrpheusColors.antNeonMagenta,
        OrpheusColors.antNeonCyan, OrpheusColors.antNeonCyan,
        OrpheusColors.antNeonOrange, OrpheusColors.antNeonOrange,
        OrpheusColors.antNeonGreen, OrpheusColors.antNeonGreen,
    )
    private val levels = floatArrayOf(0.7f, 0.2f, 0.5f, 0.1f, 0f, 0.3f)
    private val width = 800
    private val height = 500

    private fun mound(): Grid {
        val grid = Grid()
        val rnd = Random(3)
        for (x in 0 until GRID_W) for (z in 0 until GRID_D) {
            val d = hypot(x - GRID_W / 2f + 0.5f, z - GRID_D / 2f + 0.5f)
            val top = (10f - d * 1.1f + rnd.nextFloat() * 1.5f).toInt()
            for (y in 0..top.coerceAtMost(GRID_H - 1)) grid.set(x, y, z, ((d / 2.2f).toInt() + y / 4) % 4 * 2 + 1)
        }
        return grid
    }

    /** RGBA bytes of the pods over black, drawn through the layer unless [cpuField]. */
    private fun render(
        cpuField: Boolean, dx: Float = 0f, dy: Float = 0f, source: String = PodsSksl.LAYER_SOURCE,
        effects: (PodField) -> Unit = {},
    ): ByteArray {
        val grid = mound()
        val pods = PodField()
        val podLayer = PodLayer()
        val scene = ImageComposeScene(width, height, Density(1f)) {
            val field = remember { PlasmaShader(PodsSksl.SOURCE) }
            val shader = remember { LayerShader(source, "content") }
            val layer = rememberGraphicsLayer()
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black)
                pods.clearEffects()
                effects(pods)
                translate(dx, dy) {
                    if (cpuField) {
                        pods.splat(grid, voiceColors, levels, 0.5f, 0.3f, size.width, size.height)
                        drawPods(field, pods)
                    } else {
                        podLayer.draw(this, shader, layer, pods, grid, voiceColors, levels, 0.5f, 0.3f, emptyArray())
                    }
                }
            }
        }
        try {
            return scene.render(0).use { image ->
                Bitmap.makeFromImage(image).readPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))!!
            }
        } finally {
            scene.close()
        }
    }

    private fun meanDiff(a: ByteArray, b: ByteArray): Float {
        var sum = 0L
        for (i in a.indices step 4) sum += channelDiff(a, i, b, i)
        return sum.toFloat() / (a.size / 4)
    }

    private fun channelDiff(a: ByteArray, i: Int, b: ByteArray, j: Int): Int = max(
        abs((a[i].toInt() and 255) - (b[j].toInt() and 255)),
        max(abs((a[i + 1].toInt() and 255) - (b[j + 1].toInt() and 255)), abs((a[i + 2].toInt() and 255) - (b[j + 2].toInt() and 255))),
    )

    // A shader that shows the layer's alpha as grey reads the field back the way the melt reads it.
    private val fieldAsGrey = """
uniform float2 resolution;
uniform float2 fieldSize;
uniform float podRadius;
uniform float4 bounds;
uniform float4 shock;
uniform float3 lens;
uniform float3 heat;
uniform float texel;
uniform shader content;
half4 main(float2 p) { return half4(half3(content.eval(p).a), 1.0); }
"""

    @Test fun `layer alpha encodes the field as the CPU does where pods pile up and at their surface`() {
        val grey = render(cpuField = false, source = fieldAsGrey)
        val cpu = PodField()
        cpu.splat(mound(), voiceColors, levels, 0.5f, 0.3f, width.toFloat(), height.toFloat())
        val surface = 1f - exp(-0.3f / 1.5f)
        var deepest = 0f
        var sum = 0f; var count = 0
        var inBoth = 0; var inEither = 0
        for (ty in 0 until cpu.height) for (tx in 0 until cpu.width) {
            val c = (cpu.field[ty * cpu.width + tx].toInt() and 255) / 255f
            // A field texel's centre is the corner of four pixels here.
            val x = (((tx + 0.5f) / cpu.width) * width).toInt().coerceIn(1, width - 1)
            val y = (((ty + 0.5f) / cpu.height) * height).toInt().coerceIn(1, height - 1)
            var g = 0f
            for (j in -1..0) for (i in -1..0) g += grey[((y + j) * width + x + i) * 4].toInt() and 255
            g /= 4f * 255f
            if (c == 0f && g == 0f) continue
            deepest = max(deepest, c)
            sum += abs(c - g) * 255f
            count++
            if (c >= surface || g >= surface) inEither++
            if (c >= surface && g >= surface) inBoth++
        }
        // Deep enough that a straight additive port would have clipped: e of 0.49 is one pod's peak.
        assertTrue(deepest > 0.9f, "the mound never piled up: deepest $deepest")
        assertTrue(sum / count < 2f, "field off by ${sum / count} levels of 255")
        assertTrue(inBoth.toFloat() / inEither > 0.98f, "silhouette overlap ${inBoth.toFloat() / inEither}")
    }

    @Test fun `melted layer looks like the melted CPU field`() {
        val diff = meanDiff(render(cpuField = true), render(cpuField = false))
        assertTrue(diff < 4f, "mean pixel difference $diff of 255")
    }

    @Test fun `stomp shock lens and heat bend the layer as they bend the CPU field`() {
        val bend: (PodField) -> Unit = { pods ->
            stompShock(0.42f, width.toFloat(), height.toFloat(), pods.shock)
            magGlassLayout(0.42f, 0.58f, 0.4f, width.toFloat(), height.toFloat(), pods.lens, pods.heat, FloatArray(2))
        }
        val cpu = render(cpuField = true, effects = bend)
        val layer = render(cpuField = false, effects = bend)
        val moved = meanDiff(layer, render(cpuField = false))
        assertTrue(moved > 2f, "the effects barely changed the layer: $moved")
        assertTrue(meanDiff(cpu, layer) < 4f, "mean pixel difference ${meanDiff(cpu, layer)} of 255")
    }

    // The stomp's shake moves the layer: the melt's coordinates must stay the layer's own.
    @Test fun `a translated layer draws the same pods moved`() {
        val still = render(cpuField = false)
        val shifted = render(cpuField = false, dx = 40f, dy = 30f)
        var sum = 0L
        var count = 0
        for (y in 30 until height) for (x in 40 until width) {
            sum += channelDiff(still, ((y - 30) * width + x - 40) * 4, shifted, (y * width + x) * 4)
            count++
        }
        assertTrue(sum.toFloat() / count < 0.5f, "shifted layer differs by ${sum.toFloat() / count}")
    }

    @Test fun `an unchanged frame keeps its effect`() {
        val shader = LayerShader(PodsSksl.LAYER_SOURCE, "content")
        val pods = PodField()
        val podLayer = PodLayer()
        val first = podLayer.effect(shader, pods, 800f, 500f)
        val again = podLayer.effect(shader, pods, 800f, 500f)
        pods.shock[3] = 0.5f
        val moved = podLayer.effect(shader, pods, 800f, 500f)
        assertTrue(first === again, "rebuilt the effect with nothing changed")
        assertTrue(first !== moved, "kept the effect after a uniform moved")
    }
}
