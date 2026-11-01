package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.balch.orpheus.features.cowbell.shake.FILL_PLANE_Y
import org.balch.orpheus.features.cowbell.shake.FillPhysics
import org.balch.orpheus.features.cowbell.shake.FillStrikes
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FillPainterTest {

    private val size = Size(160f, 300f)
    private val camera = BellCamera.forMode(CowbellMode.SHAKE)

    private fun settled(fill: CowbellFill): FillPhysics {
        val physics = FillPhysics(fill, Random(7))
        val out = FillStrikes()
        repeat(240) {
            physics.advance(1.0 / 60.0, 0.6f, 0.2f, 0f, 0f, out)
            out.drain { }
        }
        return physics
    }

    private fun draw(painter: FillPainter, physics: FillPhysics, fill: CowbellFill): ImageBitmap {
        val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
        val frame = assertNotNull(meshFrame(size, camera))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
            painter.draw(this, fill, physics.particles, camera, pull = 0f, frame = frame, flash = 0f)
        }
        return bitmap
    }

    @Test
    fun fillPainterAllocatesNothing() {
        for (fill in CowbellFill.entries) {
            val painter = FillPainter()
            val physics = settled(fill)
            draw(painter, physics, fill)
            val clip = painter.clip
            val brushes = painter.brushBuilds
            draw(painter, physics, fill)
            draw(painter, physics, fill)
            assertSame(clip, painter.clip, "$fill: the clip path was replaced")
            assertEquals(brushes, painter.brushBuilds, "$fill: a brush was rebuilt on a later frame")
        }
    }

    @Test
    fun fillStaysInsideTheMouth() {
        for (fill in CowbellFill.entries) {
            val physics = settled(fill)
            val frame = assertNotNull(meshFrame(size, camera))
            val bitmap = draw(FillPainter(), physics, fill)
            val pixels = bitmap.toPixelMap()
            val rim = frame.rim
            val left = rim.minOf { it.x }
            val right = rim.maxOf { it.x }
            val top = rim.minOf { it.y }
            val bottom = rim.maxOf { it.y }
            var painted = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha < 0.02f) continue
                painted++
                assertTrue(x >= left - 1 && x <= right + 1 && y >= top - 1 && y <= bottom + 1, "$fill painted ($x,$y) outside the mouth box")
            }
            assertTrue(painted > 20, "$fill painted nothing")
        }
    }

    @Test
    fun planeAxesInTheShakeView() {
        val view = PlaneView()
        view.set(camera, pull = 0f)
        view.project(0f, 0f)
        val ox = view.px
        val oy = view.py
        view.project(1f, 0f)
        // The bell's +x runs up the screen and +z runs to the left from above.
        assertTrue(view.py < oy - 0.1f && kotlin.math.abs(view.px - ox) < 0.05f, "x axis ${view.px - ox},${view.py - oy}")
        view.project(0f, 1f)
        assertTrue(view.px < ox - 0.1f && kotlin.math.abs(view.py - oy) < 0.05f, "z axis ${view.px - ox},${view.py - oy}")
    }

    @Test
    fun planeViewMatchesTheMeshProjection() {
        val projector = BellProjector(size, camera)
        val view = PlaneView()
        for (pull in listOf(-1f, 0f, 0.6f)) {
            view.set(camera, pull)
            val expected = projector.project(camera.rotate(bend(Vec3(0.3f, FILL_PLANE_Y, -0.2f), pull)))
            view.project(0.3f, -0.2f)
            assertEquals(expected.x, size.width / 2f + view.px * projector.scale, 1e-3f, "x at pull $pull")
            assertEquals(expected.y, size.height / 2f + view.py * projector.scale, 1e-3f, "y at pull $pull")
            assertEquals(expected.s, view.s, 1e-5f, "s at pull $pull")
        }
    }
}
