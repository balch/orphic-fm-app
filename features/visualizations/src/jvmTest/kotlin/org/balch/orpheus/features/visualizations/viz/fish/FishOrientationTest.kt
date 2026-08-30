package org.balch.orpheus.features.visualizations.viz.fish

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Draws the fish sprite headlessly and inspects the result, because "swims upside down" is a
 * pixel fact rather than a numeric one. Renders are wrapped so a machine without skia natives
 * skips instead of failing; assertions run only on frames that actually rendered.
 *
 * ./gradlew :features:visualizations:jvmTest --tests '*FishOrientationTest*' --rerun
 */
class FishOrientationTest {

    @Test
    fun `roll target flips once the nose passes vertical and holds near it`() {
        assertTrue(rollTargetFor(0f, 1f) == 1f, "swimming right stays upright")
        assertTrue(rollTargetFor(PI.toFloat(), 1f) == -1f, "swimming left rolls over")
        assertTrue(rollTargetFor(-PI.toFloat(), 1f) == -1f, "wrapped leftward heading rolls over")
        // smoothHeading is integrated, not wrapped, so it can wander well outside +/-PI.
        assertTrue(rollTargetFor(2f * PI.toFloat(), -1f) == 1f, "unwrapped rightward heading")
        assertTrue(rollTargetFor(3f * PI.toFloat(), 1f) == -1f, "unwrapped leftward heading")

        // Straight down: no upright side to pick, so whatever the fish had is kept.
        val vertical = PI.toFloat() / 2f
        assertTrue(rollTargetFor(vertical, 1f) == 1f)
        assertTrue(rollTargetFor(vertical, -1f) == -1f)
    }

    @Test
    fun `the back points up at every non-vertical heading`() {
        for (step in 0 until 32) {
            val heading = step * 2f * PI.toFloat() / 32f
            if (abs(cos(heading)) < 0.2f) continue  // near vertical, mid-roll by design
            val y = dorsalScreenY(heading, rollTargetFor(heading, 1f))
            assertTrue(y < 0f, "heading ${heading.degrees()} put the fish's back down (y=$y)")
        }
    }

    /**
     * The end-to-end check: find the white eye in the rendered pixels and confirm it sits on
     * the upward side of the fish's spine, and ahead of centre. Before the roll fix, every
     * leftward heading put the eye below the spine.
     */
    @Test
    fun `rendered eye sits above the spine and ahead of centre`() {
        var checked = 0
        for (step in 0 until 16) {
            val heading = step * 2f * PI.toFloat() / 16f
            if (abs(cos(heading)) < 0.2f) continue
            val image = renderSingleFish(heading, rollTargetFor(heading, 1f)) ?: continue
            val eye = assertNotNull(
                whiteCentroid(image),
                "heading ${heading.degrees()}: no eye pixels found",
            )

            val dx = eye.x - image.width / 2f
            val dy = eye.y - image.height / 2f
            val along = dx * cos(heading) + dy * sin(heading)
            val perpY = dy - along * sin(heading)

            assertTrue(along > 0f, "heading ${heading.degrees()}: eye is behind the fish (along=$along)")
            assertTrue(perpY < -1f, "heading ${heading.degrees()}: eye is below the spine (perpY=$perpY)")
            checked++
        }
        if (checked == 0) println("[fish-render] no frames rendered; skia natives unavailable")
    }

    /**
     * Diagnostic only. Writes two sheets to build/aquarium-render/: fish at 16 headings with a
     * white travel line through each, and a fish rolling through a turn.
     */
    @Test
    fun `write contact sheets`() {
        val outDir = File("build/aquarium-render").apply { mkdirs() }
        runCatching {
            File(outDir, "headings.png").writeBytes(renderHeadingGrid())
            File(outDir, "roll-transition.png").writeBytes(renderRollStrip())
            println("[fish-render] wrote sheets to ${outDir.absolutePath}")
        }.onFailure { println("[fish-render] contact sheets skipped: $it") }
    }
}

private const val FISH_ORANGE = 0xFFFF7043

private fun Float.degrees(): Int = (this * 180f / PI.toFloat()).toInt()

private fun fishAt(x: Float, y: Float, heading: Float, roll: Float, size: Float) = Fish(
    id = 0,
    baseColor = Color(FISH_ORANGE),
    baseSize = size,
    x = x,
    y = y,
    heading = heading,
    smoothHeading = heading,
    roll = roll,
    rollTarget = roll,
)

/** Renders one centred fish on black, or null when skia natives are unavailable. */
private fun renderSingleFish(heading: Float, roll: Float): BufferedImage? = runCatching {
    val png = renderScene(400, 400) {
        val body = Path()
        val tail = Path()
        drawFish(fishAt(0.5f, 0.5f, heading, roll, size = 0.3f), size.width, size.height, body, tail)
    }
    ImageIO.read(ByteArrayInputStream(png))
}.getOrElse {
    println("[fish-render] heading ${heading.degrees()} skipped: $it")
    null
}

private fun renderHeadingGrid(): ByteArray = renderScene(800, 800) {
    val body = Path()
    val tail = Path()
    for (step in 0 until 16) {
        val heading = step * 2f * PI.toFloat() / 16f
        val cellX = (step % 4) * 0.25f + 0.125f
        val cellY = (step / 4) * 0.25f + 0.125f
        // White travel line so the sheet shows heading and body orientation together.
        val reach = 90f
        drawLine(
            color = Color(0x40FFFFFF),
            start = Offset(cellX * size.width - cos(heading) * reach, cellY * size.height - sin(heading) * reach),
            end = Offset(cellX * size.width + cos(heading) * reach, cellY * size.height + sin(heading) * reach),
            strokeWidth = 2f,
        )
        drawFish(
            fishAt(cellX, cellY, heading, rollTargetFor(heading, 1f), size = 0.14f),
            size.width,
            size.height,
            body,
            tail,
        )
    }
}

/** A fish turning through vertical, sampled across the roll ease. */
private fun renderRollStrip(): ByteArray = renderScene(800, 200) {
    val body = Path()
    val tail = Path()
    var roll = 1f
    for (step in 0 until 8) {
        drawFish(
            fishAt(step * 0.125f + 0.0625f, 0.5f, heading = 1.7f, roll = roll, size = 0.1f),
            size.width,
            size.height,
            body,
            tail,
        )
        roll += (-1f - roll) * ROLL_EASE
    }
}

private fun renderScene(
    width: Int,
    height: Int,
    onDraw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
): ByteArray {
    val scene = ImageComposeScene(width, height, Density(1f)) {
        Canvas(Modifier.fillMaxSize().background(Color.Black), onDraw)
    }
    return try {
        scene.render().encodeToData()!!.bytes
    } finally {
        scene.close()
    }
}

/** Centroid of the near-white pixels, which is the eye ring; the body is orange. */
private fun whiteCentroid(image: BufferedImage): Offset? {
    var sumX = 0.0
    var sumY = 0.0
    var count = 0
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            if (r > 230 && g > 230 && b > 230) {
                sumX += x
                sumY += y
                count++
            }
        }
    }
    return if (count < 8) null else Offset((sumX / count).toFloat(), (sumY / count).toFloat())
}
