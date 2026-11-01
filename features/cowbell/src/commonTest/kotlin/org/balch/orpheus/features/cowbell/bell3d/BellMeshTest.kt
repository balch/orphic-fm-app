package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BellMeshTest {

    private val size = Size(320f, 330f)

    private fun frame(mode: CowbellMode, size: Size = this.size, pull: Float = 0f): MeshFrame =
        assertNotNull(meshFrame(size, BellCamera.forMode(mode), pull))

    private fun List<MeshItem>.faces(): List<MeshFace> =
        filterIsInstance<MeshItem.Face>().map { it.face }

    private fun centroid(points: List<Offset>): Offset =
        Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

    @Test
    fun `tap view sees the top and two sides from outside`() {
        val faces = frame(CowbellMode.TAP).items.faces()
        assertEquals(5, faces.size)
        val outside = faces.filter { it.outside }
        assertEquals(3, outside.size)
        assertTrue(faces.single { it.isTop }.outside, "top face is outside")
    }

    @Test
    fun `shake view looks into the mouth`() {
        val frame = frame(CowbellMode.SHAKE)
        val faces = frame.items.faces()
        val top = faces.single { it.isTop }
        val sides = faces - top
        assertEquals(4, sides.size)
        assertTrue(sides.all { !it.outside }, "every side face is inside")
        val rim = frame.rim
        val c = centroid(top.points)
        assertTrue(
            c.x in rim.minOf { it.x }..rim.maxOf { it.x } && c.y in rim.minOf { it.y }..rim.maxOf { it.y },
            "rim contains the top centroid",
        )
    }

    @Test
    fun `items are painted back to front`() {
        CowbellMode.entries.forEach { mode ->
            val depths = frame(mode).items.map { it.depth }
            assertEquals(depths.sorted(), depths, "mode $mode")
        }
    }

    @Test
    fun `every view fits its box`() {
        listOf(Size(135f, 290f), Size(320f, 330f), Size(160f, 120f)).forEach { box ->
            CowbellMode.entries.forEach { mode ->
                listOf(1f, -1f).forEach { pull ->
                    val b = frame(mode, box, pull).bounds
                    val mx = box.width * 0.02f
                    val my = box.height * 0.02f
                    val msg = "$mode $box pull=$pull $b"
                    assertTrue(b.left >= mx - EPS && b.right <= box.width - mx + EPS, msg)
                    assertTrue(b.top >= my - EPS && b.bottom <= box.height - my + EPS, msg)
                }
            }
        }
    }

    @Test
    fun `a zero size gives no frame`() {
        val cam = BellCamera.forMode(CowbellMode.TAP)
        assertNull(meshFrame(Size(0f, 300f), cam))
        assertNull(meshFrame(Size.Zero, cam))
    }

    @Test
    fun `camera lerp ends on its targets`() {
        val from = BellCamera.forMode(CowbellMode.TAP)
        val to = BellCamera.forMode(CowbellMode.SHAKE)
        assertEquals(from, from.lerp(to, 0f))
        assertEquals(to, from.lerp(to, 1f))
        assertEquals((from.pitch + to.pitch) / 2f, from.lerp(to, 0.5f).pitch, 1e-5f)
    }

    @Test
    fun `pull stretches toward the bend`() {
        val rest = frame(CowbellMode.TAP, pull = 0f)
        val pulled = frame(CowbellMode.TAP, pull = 1f)
        val restTop = rest.items.faces().single { it.isTop }
        val pulledTop = pulled.items.faces().single { it.isTop }
        assertTrue(centroid(pulledTop.points).y < centroid(restTop.points).y, "top centre sits higher")
        assertTrue(abs(centroid(pulled.rim).y - centroid(rest.rim).y) <= 0.5f, "mouth centre holds")
        assertTrue(abs(centroid(pulled.rim).x - centroid(rest.rim).x) <= 0.5f, "mouth centre holds")
    }


    @Test
    fun swayAddsTilt() {
        val tap = BellCamera.forMode(CowbellMode.TAP)
        assertEquals(tap.yaw + 10.8f, tap.swayed(1f, 0f, CowbellMode.TAP).yaw, 1e-4f)
        assertEquals(tap.pitch, tap.swayed(1f, 0f, CowbellMode.TAP).pitch, 1e-4f)
        val shake = BellCamera.forMode(CowbellMode.SHAKE)
        assertEquals(shake.pitch + 4f, shake.swayed(0f, 1f, CowbellMode.SHAKE).pitch, 1e-4f)
        assertEquals(tap.swayed(1f, 0f, CowbellMode.TAP), tap.swayed(5f, 0f, CowbellMode.TAP))
        assertEquals(tap, tap.swayed(Float.NaN, Float.NaN, CowbellMode.TAP))
        assertEquals(tap.roll, tap.swayed(1f, 1f, CowbellMode.TAP).roll)
    }

    @Test
    fun swayedTapBellStaysInsideItsBox() {
        val box = Size(160f, 300f)
        for (mode in CowbellMode.entries) {
            for (tx in listOf(-1f, 0f, 1f)) for (ty in listOf(-1f, 0f, 1f)) {
                val camera = BellCamera.forMode(mode).swayed(tx, ty, mode)
                val bounds = assertNotNull(meshFrame(box, camera)).bounds
                val pad = 0.03f * box.width
                assertTrue(bounds.left >= -pad && bounds.right <= box.width + pad, "$mode $tx,$ty x $bounds")
                assertTrue(bounds.top >= -pad && bounds.bottom <= box.height + pad, "$mode $tx,$ty y $bounds")
            }
        }
    }

    private companion object {
        const val EPS = 0.01f
    }
}
