package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BellPainterTest {

    private val size = Size(160f, 300f)
    private val blobs = BlobField()

    @Test
    fun `blobs stay smooth after hours`() {
        val t = 36_000.0
        repeat(BellPalette.size) { i ->
            val a = blobs.blob(i, t, flash = 0f, size = size, scale = 60f).center
            val b = blobs.blob(i, t + 1.0 / 60.0, flash = 0f, size = size, scale = 60f).center
            assertTrue((a - b).getDistance() < 0.02f * size.width, "blob $i jumped from $a to $b")
        }
    }

    @Test
    fun `a flash grows the blobs`() {
        repeat(BellPalette.size) { i ->
            val calm = blobs.blob(i, 3.0, flash = 0f, size = size, scale = 60f).radius
            val struck = blobs.blob(i, 3.0, flash = 1f, size = size, scale = 60f).radius
            assertTrue(struck > calm, "blob $i: $struck is not over $calm")
        }
    }

    @Test
    fun `painted strokes stay inside the box`() {
        listOf(Density(1f), Density(2.5f)).forEach { density ->
            listOf(Size(135f, 290f), Size(160f, 300f), Size(320f, 330f), Size(160f, 120f)).forEach { box ->
                CowbellMode.entries.forEach { mode ->
                    listOf(-1f, 0f, 1f).forEach { pull ->
                        val camera = BellCamera.forMode(mode)
                        val padded = assertNotNull(density.paddedMeshFrame(box, camera, pull))
                        val msg = "$mode $box pull=$pull density=${density.density}"
                        val rimHalf = with(density) { RIM_WIDE_MAX_HALF_DP.toPx() }
                        padded.frame.rim.forEach { assertInside(box, it + padded.offset, rimHalf, "rim $msg") }
                        val handle = padded.frame.items.filterIsInstance<MeshItem.Handle>().single().points
                        val handleHalf = HANDLE_OUTER_WIDTH / 2f * padded.frame.scale * handle[handle.size / 2].s
                        handle.forEach { assertInside(box, Offset(it.x, it.y) + padded.offset, handleHalf, "handle $msg") }
                    }
                }
            }
        }
    }

    private fun assertInside(box: Size, at: Offset, half: Float, msg: String) {
        val r = Rect(at.x - half, at.y - half, at.x + half, at.y + half)
        val eps = 0.01f
        assertTrue(r.left >= -eps && r.top >= -eps && r.right <= box.width + eps && r.bottom <= box.height + eps, "$msg $r")
    }
}
