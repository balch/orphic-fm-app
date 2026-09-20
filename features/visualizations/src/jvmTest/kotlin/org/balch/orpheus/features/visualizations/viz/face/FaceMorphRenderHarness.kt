package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.VizStage
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the dissolve at fixed points so it can be looked at without playing a song, and asserts
 * the pixels are not just a black screen (a broken shader still compiles and renders black).
 * Output: features/visualizations/build/face-morph-render/
 *
 * ./gradlew :features:visualizations:jvmTest --tests '*FaceMorphRenderHarness*' --rerun
 */
class FaceMorphRenderHarness {
    private val width = 1280
    private val height = 800

    @Test fun `render the morph at fixed points and verify it is not a black screen`() {
        val faces = faceFiles().map { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
        val bias = buildFaceBiasMap()
        val dir = File("build/face-morph-render").apply { mkdirs() }
        val pixels = LinkedHashMap<String, PixelMap>()

        // Non-integer morph values so the shots actually land mid-dissolve; 0 and 1 are the ends.
        for ((label, morph, flicker, glitch) in listOf(
            Shot("000", 0f, 0f, 0f), Shot("010", 0.10f, 0f, 0f), Shot("037", 0.37f, 0f, 0f),
            Shot("058", 0.58f, 0f, 0f), Shot("083", 0.83f, 0f, 0f), Shot("100", 1f, 0f, 0f),
            Shot("030_kick", 0.3f, 0.6f, 0f), Shot("050_snare", 0.5f, 0f, 1f),
        )) {
            val blend = stageBlend(morph, faces.size)
            val inputs = FaceMorphInputs(
                faces[blend.stage], faces[blend.stage + 1], bias, blend.t, flicker, glitch, 0.6f, 0f, 3f,
            )
            val scene = ImageComposeScene(width, height, Density(1f)) {
                FaceMorphCanvas(Modifier.fillMaxSize()) { inputs }
            }
            try {
                val bytes = scene.render().encodeToData()!!.bytes
                File(dir, "morph_$label.png").writeBytes(bytes)
                pixels[label] = Image.makeFromEncoded(bytes).toComposeImageBitmap().toPixelMap()
            } finally {
                scene.close()
            }
        }
        assertTrue(pixels.size == 8)

        val midLuminance = centerMeanLuminance(pixels.getValue("037"))
        assertTrue(midLuminance > 0.08f, "morph_037 looks like a black screen: luminance $midLuminance")

        val first = centerMeanColor(pixels.getValue("000"))
        val last = centerMeanColor(pixels.getValue("100"))
        val maxChannelDelta = first.indices.maxOf { abs(first[it] - last[it]) }
        assertTrue(maxChannelDelta > 0.05f, "morph_000 and morph_100 look the same: $first vs $last")

        pixels.forEach { (label, px) ->
            corners().forEach { (x, y) ->
                val c = px[x, y]
                val brightest = maxOf(c.red, c.green, c.blue)
                assertTrue(brightest < 0.03f, "$label corner ($x,$y) does not fade to black: $brightest")
            }
        }
    }

    @Test fun `render the broadcast scene in colour and black and white`() {
        val faces = faceFiles().map { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
        val bias = buildFaceBiasMap()
        val dir = File("build/face-morph-render").apply { mkdirs() }
        val shots = LinkedHashMap<String, PixelMap>()

        for (shot in listOf(
            SceneShot("colour_wide", 1280, 800, 0.10f, 0f),
            SceneShot("mono_wide", 1280, 800, 0.83f, 1f),
            SceneShot("colour_tall", 420, 900, 0.37f, 0f),
            SceneShot("mono_tall", 420, 900, 1f, 1f),
            // Same pixel window at Retina density: the picture must not change size with it.
            SceneShot("colour_wide_2x", 1280, 800, 0.10f, 0f, density = 2f),
            // The two shapes the DJ app actually reports: a phone with a header and a bottom
            // nav, and a desktop window with the TV top and bottom bars.
            SceneShot("stage_phone", 360, 780, 0.37f, 0f, stage = Rect(0f, 44f, 360f, 700f)),
            SceneShot("stage_desktop", 1440, 900, 0.83f, 1f, stage = Rect(0f, 60f, 1440f, 752f)),
            // Same phone stage with the chrome painted in, which is the case the picture has to
            // survive: a header above and a nav bar below, both opaque.
            SceneShot(
                "stage_phone_chrome", 360, 780, 0.37f, 0f,
                stage = Rect(0f, 44f, 360f, 700f), chrome = true,
            ),
            // The same two wide shots on a percussion hit, so the tear can be looked at against
            // them rather than described.
            SceneShot("glitch_colour_wide", 1280, 800, 0.10f, 0f, glitch = 1f),
            SceneShot("glitch_mono_wide", 1280, 800, 0.83f, 1f, glitch = 1f),
        )) {
            val blend = stageBlend(shot.morph, faces.size)
            val face = FaceMorphInputs(
                faces[blend.stage], faces[blend.stage + 1], bias, blend.t,
                flicker = 0f, glitch = 0f, crt = 0f, mono = shot.mono, time = SCENE_TIME,
            )
            val vizStage = shot.stage?.let { VizStage().apply { report(it) } }
            val scene = ImageComposeScene(shot.w, shot.h, Density(shot.density)) {
                CompositionLocalProvider(LocalVizStage provides vizStage) {
                    // The host paints the void behind the set; stand in for it so the shot matches.
                    Box(Modifier.fillMaxSize().background(OrpheusColors.darkVoid)) {
                        val frame = BroadcastFrame(
                            face, level = 0.5f, crt = 0.5f, glitch = shot.glitch, time = SCENE_TIME,
                        )
                        NewsBroadcastScene(Modifier.fillMaxSize()) { frame }
                        if (shot.chrome && shot.stage != null) {
                            ChromeBars(shot.stage, shot.h)
                        }
                    }
                }
            }
            try {
                val bytes = scene.render().encodeToData()!!.bytes
                File(dir, "scene_${shot.label}.png").writeBytes(bytes)
                shots[shot.label] = Image.makeFromEncoded(bytes).toComposeImageBitmap().toPixelMap()
            } finally {
                scene.close()
            }

            val px = shots.getValue(shot.label)
            // The void must still be the void: only the room glow may touch the far corners, and
            // only faintly. Anything opaque painted full-window would move these well off it.
            // A chrome shot paints its own bars into those corners, so it is a look, not a check.
            val void = OrpheusColors.darkVoid
            if (!shot.chrome) listOf(0 to 0, (shot.w - 1) to 0, 0 to (shot.h - 1), (shot.w - 1) to (shot.h - 1))
                .forEach { (x, y) ->
                    val c = px[x, y]
                    val drift = maxOf(c.red - void.red, c.green - void.green, c.blue - void.blue)
                    val under = maxOf(void.red - c.red, void.green - c.green, void.blue - c.blue)
                    assertTrue(
                        drift < 0.05f && under < 0.01f,
                        "${shot.label} corner ($x,$y) is not the void: $c vs $void",
                    )
                }

            val layout = tvLayout(Size(shot.w.toFloat(), shot.h.toFloat()), shot.stage)
            if (shot.mono > 0.5f) {
                sampleGrid(layout.screen).forEach { (x, y) ->
                    val c = px[x, y]
                    val spread = maxOf(abs(c.red - c.green), abs(c.green - c.blue), abs(c.red - c.blue))
                    assertTrue(spread < 0.04f, "${shot.label} has colour at ($x,$y): $c")
                }
            }
        }

        val wideBannerColour = meanOf(shots.getValue("colour_wide"), tvLayout(Size(1280f, 800f)).banner)
        assertTrue(
            wideBannerColour[0] > wideBannerColour[1] + 0.25f && wideBannerColour[0] > wideBannerColour[2] + 0.25f,
            "colour banner is not red: ${wideBannerColour.toList()}",
        )
        val wideBannerMono = meanOf(shots.getValue("mono_wide"), tvLayout(Size(1280f, 800f)).banner)
        assertTrue(wideBannerMono.average() > 0.7f, "mono banner is not a light bar: ${wideBannerMono.toList()}")
    }

    /**
     * Stand-in header and nav bars at the stage's own edges, so the set can be judged in the
     * place it actually has rather than against an empty window. Diagnostic only, never asserted.
     */
    @Composable
    private fun ChromeBars(stage: Rect, windowHeight: Int) {
        val density = LocalDensity.current
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .height(with(density) { stage.top.toDp() })
                    .fillMaxWidth()
                    .background(Color(0xFF241C33)),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .height(with(density) { (windowHeight - stage.bottom).toDp() })
                    .fillMaxWidth()
                    .background(Color(0xFF241C33)),
            )
        }
    }

    /** Pixels well inside a rect, away from the antialiased border. */
    private fun sampleGrid(rect: Rect, steps: Int = 14): List<Pair<Int, Int>> {
        val pad = 3f
        return (0 until steps).flatMap { iy ->
            (0 until steps).map { ix ->
                val fx = (ix + 0.5f) / steps
                val fy = (iy + 0.5f) / steps
                ((rect.left + pad) + fx * (rect.width - 2 * pad)).toInt() to
                    ((rect.top + pad) + fy * (rect.height - 2 * pad)).toInt()
            }
        }
    }

    private fun meanOf(px: PixelMap, rect: Rect): FloatArray {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        sampleGrid(rect, steps = 24).forEach { (x, y) ->
            val c = px[x, y]
            r += c.red; g += c.green; b += c.blue; n++
        }
        return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    /**
     * The picture is sized in screen pixels, so no label may grow with the display's density or
     * the user's text scale. This is what the density-1 render shots cannot see.
     */
    @Test fun `every label fits its box at any density and text scale`() {
        val resolver = createFontFamilyResolver()
        SCENE_DENSITIES.forEach { density ->
            SCENE_WINDOWS.forEach { window ->
                val layout = tvLayout(window)
                val measurer = TextMeasurer(resolver, density, LayoutDirection.Ltr)
                measureBroadcastText(layout, measurer, density).all().forEach { fitted ->
                    listOf("colour" to fitted.colour, "mono" to fitted.mono).forEach { (mode, t) ->
                        assertTrue(
                            t.size.width <= fitted.maxWidth + 0.5f,
                            "$mode text is ${t.size.width} wide, box is ${fitted.maxWidth} at $density $window",
                        )
                        assertTrue(
                            t.size.height <= fitted.maxHeight + 0.5f,
                            "$mode text is ${t.size.height} tall, box is ${fitted.maxHeight} at $density $window",
                        )
                    }
                }
            }
        }
    }

    /**
     * The picture is sized in screen pixels, so the same window must produce the same label
     * pixels on a Retina desktop, a phone, or with the user's text size turned up.
     */
    @Test fun `label size does not follow the display density or the text scale`() {
        val resolver = createFontFamilyResolver()
        SCENE_WINDOWS.forEach { window ->
            val layout = tvLayout(window)
            fun measuredAt(density: Density) = measureBroadcastText(
                layout, TextMeasurer(resolver, density, LayoutDirection.Ltr), density,
            ).all().map { it.colour.size }

            val reference = measuredAt(Density(1f))
            SCENE_DENSITIES.drop(1).forEach { density ->
                measuredAt(density).forEachIndexed { i, size ->
                    val ref = reference[i]
                    val widthDrift = abs(size.width - ref.width) / ref.width.toFloat()
                    val heightDrift = abs(size.height - ref.height) / ref.height.toFloat()
                    assertTrue(
                        widthDrift < 0.08f && heightDrift < 0.08f,
                        "label $i is $size at $density but $ref at density 1, window $window",
                    )
                }
            }
        }
    }

    private data class SceneShot(
        val label: String,
        val w: Int,
        val h: Int,
        val morph: Float,
        val mono: Float,
        val density: Float = 1f,
        /** Null keeps the whole window, which is what the four original shots show. */
        val stage: Rect? = null,
        /** Paints stand-in header and nav bars at the stage's edges. */
        val chrome: Boolean = false,
        /** Strength of the whole-picture tear; 0 is the resting picture. */
        val glitch: Float = 0f,
    )

    private fun corners(): List<Pair<Int, Int>> = listOf(
        0 to 0, (width - 1) to 0, 0 to (height - 1), (width - 1) to (height - 1),
    )

    private fun centerMeanColor(px: PixelMap, fraction: Float = 0.4f): FloatArray {
        val x0 = (width * (1f - fraction) / 2f).toInt()
        val x1 = width - x0
        val y0 = (height * (1f - fraction) / 2f).toInt()
        val y1 = height - y0
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = px[x, y]
                r += c.red; g += c.green; b += c.blue
                n++
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    private fun centerMeanLuminance(px: PixelMap): Float = centerMeanColor(px).average().toFloat()

    private data class Shot(val label: String, val morph: Float, val flicker: Float, val glitch: Float)

    private companion object {
        const val SAMPLE_STRIDE = 4
        const val SCENE_TIME = 3.7f

        /** Density 1 first: it is the reference the others are compared against. */
        val SCENE_DENSITIES = listOf(
            Density(1f), Density(2f), Density(3f), Density(2.75f, fontScale = 1.3f),
            Density(1f, fontScale = 0.85f),
        )
        val SCENE_WINDOWS = listOf(Size(1280f, 800f), Size(420f, 900f), Size(700f, 700f))
    }
}
