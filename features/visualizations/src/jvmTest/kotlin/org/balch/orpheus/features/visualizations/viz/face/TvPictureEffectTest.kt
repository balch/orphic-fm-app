package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import org.balch.orpheus.ui.theme.OrpheusColors
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole-picture pass: it must be invisible while nothing is hitting, tear everything the tube
 * is showing when something is, and never reach past the glass onto the cabinet.
 *
 * ./gradlew :features:visualizations:jvmTest --tests '*TvPictureEffectTest*' --rerun
 */
class TvPictureEffectTest {

    // A bad shader is otherwise only visible as an untorn picture at runtime.
    @Test fun `the picture shader compiles`() {
        RuntimeEffect.makeForShader(TvPictureShaderSource.SKSL).close()
    }

    // ─── Pure-function coverage for the epsilon gate and quantiser, no GPU/renderer needed ──────

    @Test fun `glitch below one 8-bit step is not visible, matching MorphDirector's own floor`() {
        assertTrue(!isGlitchVisible(0f))
        assertTrue(!isGlitchVisible(0.5f / 255f))
        assertTrue(isGlitchVisible(1f / 255f))
        assertTrue(isGlitchVisible(1f))
    }

    @Test fun `the pass runs exactly when the shader is handed a strength above zero`() {
        // A layer whose shader gets strength 0 draws nothing and still costs a layer and a filter.
        for (i in 0..4096) {
            val x = i / 4096f
            assertEquals(
                quantiseGlitch(x) > 0f, runsPicturePass(x),
                "glitch $x quantises to ${quantiseGlitch(x)}",
            )
        }
        assertTrue(!runsPicturePass(Float.NaN))
    }

    @Test fun `sanitizedGlitch clamps and rejects non-finite input`() {
        assertEquals(0f, sanitizedGlitch(Float.NaN))
        assertEquals(0f, sanitizedGlitch(Float.NEGATIVE_INFINITY))
        assertEquals(0f, sanitizedGlitch(-1f))
        assertEquals(1f, sanitizedGlitch(2f))
        assertEquals(0.5f, sanitizedGlitch(0.5f))
    }

    @Test fun `quantiseGlitch snaps to 64 steps and rounds to the nearest one`() {
        assertEquals(0f, quantiseGlitch(0f))
        assertEquals(1f, quantiseGlitch(1f))
        assertEquals(1f / 64f, quantiseGlitch(1f / 64f))
        // Rounds to the nearer step rather than always flooring or ceiling.
        assertEquals(2f / 64f, quantiseGlitch(1.6f / 64f))
        assertEquals(1f / 64f, quantiseGlitch(1.4f / 64f))
        // Non-finite/out-of-range input is sanitized first, same as everywhere else here.
        assertEquals(0f, quantiseGlitch(Float.NaN))
        assertEquals(1f, quantiseGlitch(2f))
    }

    @Test fun `quantiseGlitch's last non-zero step is a sub-pixel, sub-step change`() {
        // The step just above zero is what a fully decayed glitch would still render as before
        // the epsilon gate above finally takes over; both the shader's lift and its displacement
        // must stay imperceptible at that step for a given picture width.
        val step = 1f / GLITCH_QUANT_STEPS
        val lift = 0.14f * step
        val screenWidthPx = 1280f
        val displacement = 0.03f * screenWidthPx * step
        assertTrue(lift < 0.01f, "lift at the last step is not sub-step: $lift")
        assertTrue(displacement < 1f, "displacement at the last step is not sub-pixel: $displacement")
    }

    /**
     * The modifier skips the pass entirely at rest, so this forces it on at a glitch of zero: the
     * shader itself has to be an identity, or every idle frame would be resampled and softened.
     */
    @Test fun `the pass at a glitch of zero is pixel identical to no pass`() {
        val renderer = TvPictureRenderer()
        try {
            assertTrue(renderer.isSupported(), "the picture shader did not compile")
            val idle = renderer.effect(glass(W, H), glitch = 0f, mono = 0f, time = TIME)
            assertNotNull(idle, "no effect was built at a glitch of zero")

            val plain = render { scene(0f, 0f) }
            val passed = render {
                Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = idle }) { scene(0f, 0f) }
            }

            // One 8-bit step is the colour round trip through the offscreen pass. A resample, on
            // the other hand, would blow this apart at the first ticker glyph or cabinet edge.
            val (at, worst) = worstDelta(plain, passed)
            assertTrue(worst <= 2f / 255f, "the idle pass changed pixel $at by $worst")
        } finally {
            renderer.dispose()
        }
    }

    @Test fun `a glitch tears the whole picture and nothing outside the glass`() {
        val still = render { scene(0f, 0f) }
        val torn = render { scene(0f, 1f) }
        val layout = tvLayout(Size(W.toFloat(), H.toFloat()))

        val backdrop = Rect(
            layout.screen.left, layout.screen.top,
            layout.screen.left + layout.screen.width * 0.5f,
            layout.screen.top + layout.screen.height * 0.45f,
        )
        listOf("lower third" to layout.banner, "ticker" to layout.ticker, "backdrop" to backdrop)
            .forEach { (what, rect) ->
                val share = changedShare(still, torn, rect)
                assertTrue(share > 0.02f, "the $what did not tear: $share")
            }

        // The cabinet, the antenna and the void are in front of or behind the tube, never in it.
        val keepOut = layout.screen.inflate(2f)
        val (at, worst) = worstDelta(still, torn) { x, y -> !keepOut.contains(Offset(x + 0.5f, y + 0.5f)) }
        assertTrue(worst <= 2f / 255f, "the pass reached outside the glass at $at by $worst")
    }

    /**
     * A sideways slide is invisible where the picture is flat, so a torn row also smears, lifts
     * and speckles. The torn rows are read back out of the difference rather than mirrored from
     * the shader's hash, which keeps one copy of the roll instead of two that can drift apart.
     */
    @Test fun `a torn row lifts into a line over the flat backdrop`() {
        // The stand-by card hangs in this band, so it is faded out: its text sliding sideways
        // would read as lift and this is about the shader's own.
        val still = render { scene(0f, 0f, standBy = 0f) }
        val torn = render { scene(0f, 1f, standBy = 0f) }
        val screen = tvLayout(Size(W.toFloat(), H.toFloat())).screen
        // A column band of plain studio backdrop: no bug, no story box, no desk edge, so a row
        // that only slid sideways would show nothing here at all.
        val x0 = (screen.left + screen.width * 0.10f).toInt()
        val x1 = (screen.left + screen.width * 0.30f).toInt()

        val rises = (0 until ROWS).map { row ->
            val y0 = (screen.top + row * screen.height / ROWS).toInt() + 1
            val y1 = (screen.top + (row + 1) * screen.height / ROWS).toInt() - 1
            row to meanLuma(torn, x0, x1, y0, y1) - meanLuma(still, x0, x1, y0, y1)
        }
        val lit = rises.filter { it.second >= 0.05f }

        assertTrue(lit.isNotEmpty(), "no row lifted over the flat backdrop: ${rises.maxOf { it.second }}")
        // The share that tears is the photosensitivity bound, so it is asserted, not assumed.
        assertTrue(lit.size in 4..12, "${lit.size} of $ROWS rows tore, expected about 8 percent")
        val quiet = rises.filterNot { it in lit }.maxOf { abs(it.second) }
        assertTrue(quiet < 0.01f, "a row that did not tear moved by $quiet")
        // Above the desk the lift is all there is, so this bounds the flash the viewer sees.
        val brightest = lit.filter { it.first < ROWS / 2 }.maxOfOrNull { it.second } ?: 0f
        assertTrue(brightest <= 0.15f, "a torn row lifted by $brightest, past the cap")
    }

    /**
     * "Un-torn rows stay sharp" is not what the mean-luminance check above asserts: a smear
     * applied uniformly to every row would leave a FLAT band's mean unchanged, so that check would
     * pass even if every row were being softened. This checks horizontal local contrast instead,
     * over the ticker and banner text, where a smear is visible but a flat mean is not.
     */
    @Test fun `un-torn rows over the ticker and banner stay as sharp as no glitch at all`() {
        val still = render { scene(0f, 0f) }
        val torn = render { scene(0f, 1f) }
        val layout = tvLayout(Size(W.toFloat(), H.toFloat()))
        val screen = layout.screen

        // Classify rows torn/untorn the same way as the flat-backdrop test above: the tear
        // pattern depends only on row and roll, so it is uniform across the row's full width and
        // this classification also holds wherever the row crosses the ticker or the banner.
        val bx0 = (screen.left + screen.width * 0.10f).toInt()
        val bx1 = (screen.left + screen.width * 0.30f).toInt()
        val tornRows = (0 until ROWS).filter { row ->
            val y0 = (screen.top + row * screen.height / ROWS).toInt() + 1
            val y1 = (screen.top + (row + 1) * screen.height / ROWS).toInt() - 1
            meanLuma(torn, bx0, bx1, y0, y1) - meanLuma(still, bx0, bx1, y0, y1) >= 0.05f
        }.toSet()
        assertTrue(tornRows.isNotEmpty(), "no rows classified as torn; nothing to compare against")

        fun rowSamples(region: Rect): List<Triple<Int, Float, Float>> {
            val rx0 = region.left.toInt() + 4
            val rx1 = region.right.toInt() - 4
            if (rx1 <= rx0) return emptyList()
            return (0 until ROWS).mapNotNull { row ->
                val yTop = screen.top + row * screen.height / ROWS
                val yBottom = screen.top + (row + 1) * screen.height / ROWS
                val y = ((yTop + yBottom) / 2f).toInt()
                if (y <= region.top.toInt() || y >= region.bottom.toInt()) return@mapNotNull null
                Triple(row, rowContrast(still, rx0, rx1, y), rowContrast(torn, rx0, rx1, y))
            }
        }

        val samples = rowSamples(layout.ticker) + rowSamples(layout.banner)
        val (sharp, smeared) = samples.partition { it.first !in tornRows }
        assertTrue(sharp.isNotEmpty(), "no untorn rows sampled inside the ticker or banner")
        assertTrue(smeared.isNotEmpty(), "no torn rows sampled inside the ticker or banner")

        sharp.forEach { (row, plainContrast, tornContrast) ->
            assertTrue(
                abs(tornContrast - plainContrast) <= plainContrast * 0.25f + 0.01f,
                "row $row did not tear but lost contrast: plain=$plainContrast torn=$tornContrast",
            )
        }
        smeared.forEach { (row, plainContrast, tornContrast) ->
            assertTrue(
                tornContrast <= plainContrast * 0.7f,
                "row $row tore but stayed as sharp: plain=$plainContrast torn=$tornContrast",
            )
        }
    }

    /** A red/blue fringe would be the only colour on a black-and-white broadcast. */
    @Test fun `a mono picture stays colourless at full glitch`() {
        val px = render { scene(mono = 1f, glitch = 1f) }
        val screen = tvLayout(Size(W.toFloat(), H.toFloat())).screen
        // Deflated past the glass's own corner radius, so the rounded cut-outs, which show the
        // cabinet and always have, are not read as picture.
        val inner = screen.deflate(screen.height * 0.08f)
        var worst = 0f
        var worstAt = 0 to 0
        forEachSample(W, H, stride = 2) { x, y ->
            if (!inner.contains(Offset(x + 0.5f, y + 0.5f))) return@forEachSample
            val c = px[x, y]
            val spread = maxOf(abs(c.red - c.green), abs(c.green - c.blue), abs(c.red - c.blue))
            if (spread > worst) { worst = spread; worstAt = x to y }
        }
        assertTrue(worst < 0.04f, "a torn mono picture has colour at $worstAt: $worst")
    }

    @Composable
    private fun scene(mono: Float, glitch: Float, standBy: Float = 1f) {
        val faces = FACES
        val blend = stageBlend(if (mono > 0.5f) 0.83f else 0.10f, faces.size)
        val face = FaceMorphInputs(
            faces[blend.stage], faces[blend.stage + 1], BIAS, blend.t,
            flicker = 0f, glitch = 0f, crt = 0f, mono = mono, time = TIME,
        )
        Box(Modifier.fillMaxSize().background(OrpheusColors.darkVoid)) {
            val frame = BroadcastFrame(face, level = 0.5f, crt = 0.5f, glitch = glitch, time = TIME, standBy = standBy)
            NewsBroadcastScene(Modifier.fillMaxSize()) { frame }
        }
    }

    private fun render(content: @Composable () -> Unit): PixelMap {
        val scene = ImageComposeScene(W, H, Density(1f)) { content() }
        return try {
            val bytes = scene.render().encodeToData()!!.bytes
            Image.makeFromEncoded(bytes).toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
        }
    }

    private fun glass(w: Int, h: Int): Rect = tvLayout(Size(w.toFloat(), h.toFloat())).screen

    /** Worst channel difference over the sampled pixels [include] accepts, and where it was. */
    private fun worstDelta(
        a: PixelMap,
        b: PixelMap,
        include: (Int, Int) -> Boolean = { _, _ -> true },
    ): Pair<Pair<Int, Int>, Float> {
        var worst = 0f
        var at = 0 to 0
        forEachSample(W, H) { x, y ->
            if (!include(x, y)) return@forEachSample
            val p = a[x, y]
            val q = b[x, y]
            val d = maxOf(abs(p.red - q.red), abs(p.green - q.green), abs(p.blue - q.blue))
            if (d > worst) { worst = d; at = x to y }
        }
        return at to worst
    }

    private inline fun forEachSample(w: Int, h: Int, stride: Int = 3, body: (Int, Int) -> Unit) {
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                body(x, y)
                x += stride
            }
            y += stride
        }
    }

    private fun meanLuma(px: PixelMap, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val c = px[x, y]
                sum += (c.red + c.green + c.blue) / 3.0
                n++
            }
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    /**
     * Mean |luma[x+1] - luma[x]| along one row: a smear (neighbours pulled toward each other)
     * reads far lower than crisp text or graphics, so this stands in for "was this row blurred".
     */
    private fun rowContrast(px: PixelMap, x0: Int, x1: Int, y: Int): Float {
        var sum = 0.0
        var n = 0
        var prev = luma(px[x0, y])
        for (x in (x0 + 1) until x1) {
            val l = luma(px[x, y])
            sum += abs(l - prev)
            prev = l
            n++
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    private fun luma(c: androidx.compose.ui.graphics.Color): Float = (c.red + c.green + c.blue) / 3f

    /** Share of sampled pixels in [rect] that moved by more than a rounding step. */
    private fun changedShare(a: PixelMap, b: PixelMap, rect: Rect): Float {
        var moved = 0
        var seen = 0
        var y = rect.top.toInt() + 2
        while (y < rect.bottom.toInt() - 2) {
            var x = rect.left.toInt() + 2
            while (x < rect.right.toInt() - 2) {
                val p = a[x, y]
                val q = b[x, y]
                val d = maxOf(abs(p.red - q.red), abs(p.green - q.green), abs(p.blue - q.blue))
                if (d > 2f / 255f) moved++
                seen++
                x += 2
            }
            y += 2
        }
        return if (seen == 0) 0f else moved.toFloat() / seen
    }

    private companion object {
        const val W = 1280
        const val H = 800
        const val TIME = 3.7f

        /** Scanlines the shader divides the glass into; the tear picks rows out of these. */
        const val ROWS = 90

        val FACES by lazy {
            faceFiles().map { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
        }
        val BIAS by lazy { buildFaceBiasMap() }
    }
}
