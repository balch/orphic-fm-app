package org.balch.orpheus.djapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.liquid
import io.github.fletchmckee.liquid.rememberLiquidState
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test

/**
 * Answers one question before any scrim work leans on the render harness: does the liquid
 * RenderEffect actually resolve under [ImageComposeScene]'s headless Skia surface, or does it
 * silently no-op and hand back the unmodified backdrop?
 *
 * If it no-ops, every before/after PNG of a glass change is two identical images and the harness
 * is worthless for judging this feature. Asserts nothing — prints a verdict.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*LiquidHeadlessProbe*' --rerun
 */
class LiquidHeadlessProbeTest {

    @Test
    fun liquidRendersHeadlessly() {
        val outDir = File("build/djapp-render").apply { mkdirs() }

        val plain = renderProbe(lens = false)
        val lensed = renderProbe(lens = true)

        if (plain == null || lensed == null) {
            println("[liquid-probe] VERDICT: scene render failed; no skia natives?")
            return
        }

        File(outDir, "probe-plain.png").writeBytes(plain)
        File(outDir, "probe-lensed.png").writeBytes(lensed)

        val diff = meanAbsDifference(plain, lensed)
        println("[liquid-probe] identical bytes = ${plain.contentEquals(lensed)}")
        println("[liquid-probe] mean abs channel difference = $diff")
        println(
            if (diff > 0.5) "[liquid-probe] VERDICT: liquid DOES render headlessly — harness is usable"
            else "[liquid-probe] VERDICT: liquid does NOT render headlessly — before/after would be identical"
        )
        println("[liquid-probe] PNGs at ${outDir.absolutePath}")
    }

    /** Mirrors DjApp's sibling structure: backdrop is the liquefiable source, lens is a sibling. */
    private fun renderProbe(lens: Boolean): ByteArray? = runCatching {
        val scene = ImageComposeScene(480, 270, Density(1f)) {
            val liquidState = rememberLiquidState()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().liquefiable(liquidState)) { probeBackdrop() }
                if (lens) {
                    Box(
                        Modifier.fillMaxSize().liquid(liquidState) {
                            // Deliberately extreme: a subtle setting that fails to render is
                            // indistinguishable from one that renders faintly.
                            refraction = 1f
                            curve = 1f
                            edge = 12f
                            saturation = 0f
                            contrast = 2f
                            tint = Color.Cyan.copy(alpha = 0.3f)
                        }
                    )
                }
            }
        }
        try {
            scene.render().encodeToData()!!.bytes
        } finally {
            scene.close()
        }
    }.onFailure { println("[liquid-probe] render(lens=$lens) threw: $it") }.getOrNull()

    /** 0 = pixel-identical. Channel values are 0..255, so anything above ~0.5 is a real change. */
    private fun meanAbsDifference(a: ByteArray, b: ByteArray): Double {
        val ia = ImageIO.read(ByteArrayInputStream(a))
        val ib = ImageIO.read(ByteArrayInputStream(b))
        var total = 0L
        var count = 0L
        for (y in 0 until ia.height) {
            for (x in 0 until ia.width) {
                val pa = ia.getRGB(x, y)
                val pb = ib.getRGB(x, y)
                for (shift in intArrayOf(16, 8, 0)) {
                    total += abs(((pa shr shift) and 0xFF) - ((pb shr shift) and 0xFF)).toLong()
                    count++
                }
            }
        }
        return total.toDouble() / count
    }
}

/** Saturated, high-contrast backdrop so any distortion or desaturation is unmistakable. */
@Composable
private fun probeBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFFFF7A45), Color(0xFFB4438C), Color(0xFF2E1A47)),
            )
        )
        // Hard-edged bars: refraction bends them visibly, a no-op leaves them straight.
        val barWidth = size.width / 12f
        for (i in 0 until 12 step 2) {
            drawRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset(i * barWidth, 0f),
                size = androidx.compose.ui.geometry.Size(barWidth, size.height),
            )
        }
    }
}
