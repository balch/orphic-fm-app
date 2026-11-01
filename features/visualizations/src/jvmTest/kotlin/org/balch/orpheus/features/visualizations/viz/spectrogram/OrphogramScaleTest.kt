package org.balch.orpheus.features.visualizations.viz.spectrogram

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrphogramScaleTest {

    @Test fun `palette runs from near black to pale lavender through cool stops`() {
        val p = OrphogramScale.palette
        assertEquals(Color(7, 6, 12), p[0])
        assertEquals(Color(200, 195, 255), p[255])
        assertTrue(p[64].blue > p[64].red, "a quarter up is indigo, ${p[64]}")
        assertTrue(p[128].blue > 0.95f && p[128].red > p[128].green, "halfway is violet, ${p[128]}")
    }

    @Test fun `level index puts the floor rangeDb under the peak`() {
        assertEquals(255, OrphogramScale.levelIndex(-10f, peakDb = -10f, rangeDb = 70f))
        assertEquals(0, OrphogramScale.levelIndex(-80f, peakDb = -10f, rangeDb = 70f))
        assertEquals(128, OrphogramScale.levelIndex(-45f, peakDb = -10f, rangeDb = 70f))
        assertEquals(0, OrphogramScale.levelIndex(OrphogramScale.SILENT_DB, peakDb = -10f, rangeDb = 70f))
    }

    @Test fun `held peak jumps up, falls slowly and stops at the minimum`() {
        assertEquals(-6f, OrphogramScale.heldPeak(-20f, -6f, 0.1f))
        assertEquals(-20f - OrphogramScale.PEAK_RELEASE_DB_PER_SECOND, OrphogramScale.heldPeak(-20f, -40f, 1f))
        assertEquals(OrphogramScale.MIN_PEAK_DB, OrphogramScale.heldPeak(-49f, -120f, 10f))
    }

    @Test fun `rows span the axis from the lowest to the highest frequency`() {
        assertEquals(OrphogramScale.F_MIN, OrphogramScale.rowHz(0), 0.01f)
        assertEquals(OrphogramScale.F_MAX, OrphogramScale.rowHz(OrphogramScale.ROWS - 1), 0.5f)
        assertEquals(0f, OrphogramScale.axisFraction(OrphogramScale.F_MIN), 1e-6f)
        assertEquals(1f, OrphogramScale.axisFraction(OrphogramScale.F_MAX), 1e-6f)
    }

    @Test fun `ring writes columns at the span's rate and keeps a short hit through a slow scroll`() {
        val ring = OrphogramRing(cols = 100)
        val quiet = FloatArray(OrphogramScale.ROWS) { -120f }
        val hit = FloatArray(OrphogramScale.ROWS) { if (it == 200) 0f else -120f }

        // 100 columns over 10 s is 10 a second: 0.25 s writes two, with 0.5 of a column left over.
        ring.advance(quiet, 0.25f, spanSeconds = 10f, rangeDb = 70f)
        assertEquals(2, ring.head)
        assertEquals(0.5f, ring.progress, 1e-4f)

        // A frame too short for a column of its own still shows up in the next one.
        ring.advance(hit, 0.02f, spanSeconds = 10f, rangeDb = 70f)
        assertEquals(2, ring.head)
        ring.advance(quiet.copyOf(), 0.05f, spanSeconds = 10f, rangeDb = 70f)
        assertEquals(3, ring.head)

        val pixels = ring.image!!.toPixelMap()
        assertEquals(OrphogramScale.palette[255], pixels[2, OrphogramScale.ROWS - 1 - 200])
        assertEquals(OrphogramScale.palette[0], pixels[2, OrphogramScale.ROWS - 1 - 100])

        // The shader's bytes: the hit's row at full level, its field spread a few rows either side.
        fun at(bytes: ByteArray, row: Int) = bytes[(OrphogramScale.ROWS - 1 - row) * ring.cols + 2].toInt() and 0xFF
        assertEquals(255, at(ring.level, 200))
        assertEquals(0, at(ring.level, 100))
        assertTrue(at(ring.field, 202) in 1 until at(ring.field, 200))
        assertEquals(0, at(ring.field, 100))
    }
}
