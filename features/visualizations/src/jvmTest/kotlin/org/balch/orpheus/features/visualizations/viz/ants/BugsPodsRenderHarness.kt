package org.balch.orpheus.features.visualizations.viz.ants

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.visualizations.viz.shader.LayerShader
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaShader
import org.balch.orpheus.ui.theme.OrpheusColors
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test

/**
 * Renders a built-up Bugs mound as Canvas pods and as metaball pods (the sprite layer, and the CPU
 * field it falls back to), quiet and loud, and the destruction effects over it, and prints what
 * building each frame costs on the CPU. Asserts nothing.
 * Output: features/visualizations/build/bugs-render/
 *
 * ./gradlew :features:visualizations:jvmTest --tests '*BugsPodsRenderHarness*' -PrenderHarness --rerun
 */
class BugsPodsRenderHarness {
    private val voiceColors = arrayOf(
        OrpheusColors.antNeonMagenta, OrpheusColors.antNeonMagenta,
        OrpheusColors.antNeonCyan, OrpheusColors.antNeonCyan,
        OrpheusColors.antNeonOrange, OrpheusColors.antNeonOrange,
        OrpheusColors.antNeonGreen, OrpheusColors.antNeonGreen,
        OrpheusColors.antNeonYellow, OrpheusColors.antNeonYellow,
        OrpheusColors.antNeonPink, OrpheusColors.antNeonPink,
        OrpheusColors.antFireRed,
    )
    private val duoLevels = floatArrayOf(0.7f, 0.2f, 0.5f, 0.1f, 0f, 0.3f)
    private val quietLevels = FloatArray(6)
    private val loudLevels = FloatArray(6) { 0.9f }

    /** A cone-shaped mound in bands of colour, ragged at the edges, as the ants pile one up. */
    private fun mound(): Grid {
        val grid = Grid()
        val rnd = Random(3)
        for (x in 0 until GRID_W) for (z in 0 until GRID_D) {
            val d = hypot(x - GRID_W / 2f + 0.5f, z - GRID_D / 2f + 0.5f)
            val top = (10f - d * 1.1f + rnd.nextFloat() * 1.5f).toInt()
            for (y in 0..top.coerceAtMost(GRID_H - 1)) {
                val duo = ((d / 2.2f).toInt() + y / 4) % 6
                grid.set(x, y, z, duo * 2 + 1 + rnd.nextInt(2))
            }
        }
        return grid
    }

    /** Ants walking over and around the mound, in every colour, as they do mid-build. */
    private val ants = Array(36) { i ->
        val a = i * 2.4f
        val r = 0.05f + (i % 6) * 0.06f
        Ant(
            x = 0.5f + cos(a) * r, z = 0.5f + sin(a) * r, headingAngle = a,
            colorIndex = i % 12 + 1, active = true,
        )
    }

    /** The mound's column tops splashed as the stomp does it, then flown for [seconds]. */
    private fun splash(seconds: Float): Array<Debris> {
        val grid = mound()
        val rnd = Random(5)
        val tops = (0 until GRID_W * GRID_D).shuffled(rnd).mapNotNull { col ->
            val x = col % GRID_W
            val z = col / GRID_W
            (GRID_H - 1 downTo 0).firstOrNull { grid.get(x, it, z) != 0 }?.let { y -> Triple(x, y, z) }
        }
        val debris = Array(MAX_DEBRIS) { Debris() }
        for ((d, top) in debris.zip(tops)) {
            val (x, y, z) = top
            val dx = (x + 0.5f) / GRID_W - 0.5f
            val dz = (z + 0.5f) / GRID_D - 0.5f
            d.x = (x + 0.5f) / GRID_W; d.y = (y + 0.5f) / GRID_H; d.z = (z + 0.5f) / GRID_D
            d.vx = dx * 0.7f + (rnd.nextFloat() - 0.5f) * 0.3f
            d.vy = 1.0f + rnd.nextFloat() * 1.2f
            d.vz = dz * 0.7f + (rnd.nextFloat() - 0.5f) * 0.3f
            d.colorIndex = grid.get(x, y, z)
            d.life = 1f; d.decay = 0.35f + rnd.nextFloat() * 0.15f
            d.size = 0.004f + rnd.nextFloat() * 0.004f
            d.goo = true; d.active = true
        }
        repeat((seconds * 60).toInt()) { debris.forEach { if (it.active) it.step(1f / 60f) } }
        return debris
    }

    private fun render(
        name: String, width: Int, height: Int, shaderPods: Boolean,
        duoLevels: FloatArray = this.duoLevels, master: Float = 0.5f,
        grid: Grid = mound(), debris: Array<Debris> = emptyArray(),
        effects: (PodField, Float, Float) -> Unit = { _, _, _ -> },
        overlay: DrawScope.(PodField) -> Unit = {},
        cpuField: Boolean = false,
    ) {
        val pods = PodField()
        val podLayer = PodLayer()
        val scene = ImageComposeScene(width, height, Density(1f)) {
            val shader = remember { PlasmaShader(PodsSksl.SOURCE) }
            DisposableEffect(shader) { onDispose { shader.dispose() } }
            val layerShader = remember { LayerShader(PodsSksl.LAYER_SOURCE, "content") }
            DisposableEffect(layerShader) { onDispose { layerShader.dispose() } }
            val layer = rememberGraphicsLayer()
            Canvas(Modifier.fillMaxSize()) {
                drawBackground()
                drawGroundPlane()
                pods.clearEffects()
                effects(pods, size.width, size.height)
                if (shaderPods && !cpuField) {
                    podLayer.draw(this, layerShader, layer, pods, grid, voiceColors, duoLevels, master, 0.3f, debris)
                } else if (shaderPods) {
                    pods.splat(grid, voiceColors, duoLevels, master, 0.3f, size.width, size.height, debris)
                    drawPods(shader, pods)
                } else {
                    drawBlocks(grid, voiceColors, duoLevels, master, 0.3f)
                }
                drawDebris(debris, voiceColors, gooInPods = shaderPods, FloatArray(2))
                overlay(pods)
            }
        }
        try {
            val dir = File("build/bugs-render").apply { mkdirs() }
            File(dir, "$name.png").writeBytes(scene.render(0).encodeToData()!!.bytes)
        } finally {
            scene.close()
        }

        // What each path costs to prepare a frame, before any rasterizing.
        val count = 200
        val start = System.nanoTime()
        if (shaderPods) {
            repeat(count) { pods.splat(grid, voiceColors, duoLevels, 0.5f, 0.3f, width.toFloat(), height.toFloat(), debris) }
            println("[bugs] $name splat ${width}x$height (${pods.width}x${pods.height} field): ${"%.2f".format((System.nanoTime() - start) / 1e6 / count)} ms/frame")
        }
    }

    private fun DrawScope.drawAnts() = drawAnts(ants, voiceColors, Stroke(width = 1f))

    /** The stomp at [progress], with the boot where the simulation puts it then. */
    private fun stomp(name: String, progress: Float, cpuField: Boolean = false) {
        val impact = progress >= STOMP_IMPACT
        val stompY = when {
            !impact -> -0.3f + progress / STOMP_IMPACT * 0.75f
            progress < 0.4f -> 0.45f
            else -> 0.45f - (progress - 0.4f) / 0.6f * 0.8f
        }
        val phase = if (!impact) 0 else if (progress < 0.4f) 1 else 2
        render(
            name, 1200, 800, true,
            grid = if (impact) Grid() else mound(),
            debris = if (impact) splash((progress - STOMP_IMPACT) * STOMP_DURATION) else emptyArray(),
            effects = { pods, w, h -> stompShock(progress, w, h, pods.shock) },
            overlay = { pods ->
                val shake = stompShake(progress)
                translate(shake.x, shake.y) {
                    if (!impact) drawAnts()
                    drawStompEffect(progress, stompY, phase, pods.shock, Path(), Stroke(width = 3f), Stroke(width = 2f))
                }
            },
            cpuField = cpuField,
        )
    }

    @Test fun canvasPods() = render("canvas_pods", 1200, 800, shaderPods = false)
    @Test fun canvasPodsLoud() = render("canvas_pods_loud", 1200, 800, false, loudLevels, 1f)
    @Test fun metaballPods() = render("metaball_pods", 1200, 800, shaderPods = true)
    @Test fun metaballPodsCpuField() = render("metaball_pods_cpu", 1200, 800, shaderPods = true, cpuField = true)
    @Test fun metaballPodsQuiet() = render("metaball_pods_quiet", 1200, 800, true, quietLevels, 0f)
    @Test fun metaballPodsLoud() = render("metaball_pods_loud", 1200, 800, true, loudLevels, 1f)
    @Test fun metaballPodsFullscreen() = render("metaball_pods_fullscreen", 2560, 1440, shaderPods = true)

    @Test fun stompFalling() = stomp("stomp_1_falling", 0.22f)
    @Test fun stompSplash() = stomp("stomp_2_splash", 0.42f)
    @Test fun stompPuddles() = stomp("stomp_3_puddles", 0.6f)
    @Test fun stompSplashCpuField() = stomp("stomp_2_splash_cpu", 0.42f, cpuField = true)
    @Test fun stompPuddlesCpuField() = stomp("stomp_3_puddles_cpu", 0.6f, cpuField = true)

    @Test fun fireAnts() = render("fire_ants", 1200, 800, true, overlay = {
        drawAnts()
        drawFireAntEffect(0.5f, Random(7))
    })

    @Test fun magnifyingGlass() {
        val progress = 0.4f
        val cx = 0.42f
        val cz = 0.58f
        // The glass has already burnt out the columns under its focus.
        val grid = mound()
        val burn = 2f + progress * 3f
        for (x in 0 until GRID_W) for (z in 0 until GRID_D) {
            if (hypot(x - cx * GRID_W, z - cz * GRID_D) < burn) for (y in 0 until GRID_H) grid.set(x, y, z, 0)
        }
        render(
            "magnifying_glass", 1200, 800, true, grid = grid,
            effects = { pods, w, h -> magGlassLayout(cx, cz, progress, w, h, pods.lens, pods.heat, FloatArray(2)) },
            overlay = { pods ->
                drawAnts()
                drawMagnifyingGlassEffect(pods.lens, pods.heat, 1f, Path(), Stroke(width = 2f))
            },
        )
    }
}
