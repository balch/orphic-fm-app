package org.balch.orpheus.features.visualizations.viz

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrphoscopeLanesTest {
    @Test fun `lane level maps the dB range onto the lane`() {
        assertEquals(1f, laneLevel(1f))
        assertEquals(0.5f, laneLevel(0.0631f), 0.01f) // -24 dBFS
        assertEquals(0f, laneLevel(0.00398f), 0.01f)  // -48 dBFS
        assertEquals(0f, laneLevel(0f))
        assertEquals(1f, laneLevel(2f))
    }

    // Every ring here ends at sample 480, with the scroll's head there too.
    private val ends = IntArray(8) { 480 }

    @Test fun `lanes show only while a track has signal in the window`() {
        val lanes = OrphoscopeLanes(OrpheusColors.pulsarTracks)
        assertFalse(lanes.anyLive(Array(8) { FloatArray(480) }, ends, 480.0, 480))
        assertFalse(lanes.anyLive(Array(8) { FloatArray(0) }, IntArray(8), 0.0, 480))
        // A hit that has scrolled out of a zoomed-in window no longer counts.
        val old = Array(8) { FloatArray(480) }.also { it[3][10] = 0.4f }
        assertTrue(lanes.anyLive(old, ends, 480.0, 480))
        assertFalse(lanes.anyLive(old, ends, 480.0, 48))
    }

    private val width = 640
    private val height = 480
    private val colors = OrpheusColors.pulsarTracks
    private val laneH = height / 8

    private fun drawLanes(tracks: Array<FloatArray>) = render(width, height) {
        OrphoscopeLanes(colors).draw(
            this, tracks, ends, head = 480.0, visible = 460,
            area = Rect(0f, 0f, width.toFloat(), height.toFloat()), glow = 1f, pump = FloatArray(8),
        )
    }

    /** Each track's envelope lands in its own band, in its own colour: the lanes never share pixels. */
    @Test fun `every track draws inside its own lane`() {
        for (live in 0 until 8) {
            val pixels = drawLanes(Array(8) { t -> FloatArray(480) { i -> if (t == live && i % 40 == 0) 1f else 0f } })
            var envelope = 0
            for (x in 0 until width) for (y in 0 until height) {
                val c = pixels[x, y]
                // Off every centre line, so the silent lanes' flat traces are out of the picture.
                if (c.alpha <= 0.5f || abs(y % laneH - laneH / 2) <= 2) continue
                assertEquals(live, y / laneH, "track $live drew at y=$y")
                assertTrue(isTrace(c, colors[live]), "track $live drew another colour at ($x, $y)")
                envelope++
            }
            assertTrue(envelope > 0, "track $live drew nothing")
        }
    }

    @Test fun `a silent lane keeps a flat trace in its colour`() {
        val pixels = drawLanes(Array(8) { t -> FloatArray(480) { i -> if (t == 0 && i % 40 == 0) 1f else 0f } })
        for (t in 1 until 8) {
            assertTrue(isTrace(pixels[width / 2, laneH * t + laneH / 2], colors[t]), "silent lane $t vanished")
        }
    }

    // A trace pixel: bright, and the track's own hue. The faint centre lines and tints never reach this.
    private fun isTrace(c: Color, target: Color) = c.alpha > 0.5f &&
        abs(c.red - target.red) < 0.2f && abs(c.green - target.green) < 0.2f && abs(c.blue - target.blue) < 0.2f

    private fun render(width: Int, height: Int, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): PixelMap =
        ImageBitmap(width, height).also {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(width.toFloat(), height.toFloat()), block)
        }.toPixelMap()
}
