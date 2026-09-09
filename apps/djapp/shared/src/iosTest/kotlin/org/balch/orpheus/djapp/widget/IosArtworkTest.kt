package org.balch.orpheus.djapp.widget

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosArtworkTest {

    private val topLeft = 0xFF3A1D14.toInt()
    private val topRight = 0xFF1D6E3A.toInt()
    private val bottomLeft = 0xFF14297A.toInt()
    private val bottomRight = 0xFF7A1D6E.toInt()

    /** A 1080x1080 PNG in four quadrant colours, matching the metadata producer's
     *  size but also letting tests catch a misplaced or mis-scaled draw. */
    private fun sourcePng(): ByteArray {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(1080, 1080))
        val canvas = Canvas(bitmap)
        val half = 1080f / 2f
        canvas.drawRect(0f, 0f, half, half, Paint().apply { color = topLeft })
        canvas.drawRect(half, 0f, 1080f, half, Paint().apply { color = topRight })
        canvas.drawRect(0f, half, half, 1080f, Paint().apply { color = bottomLeft })
        canvas.drawRect(half, half, 1080f, 1080f, Paint().apply { color = bottomRight })
        return Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    /** Per-channel tolerance for resampling noise while still catching a blank image. */
    private fun channelsClose(a: Int, b: Int, tolerance: Int = 4) =
        (0..24 step 8).all { shift -> kotlin.math.abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) <= tolerance }

    @Test
    fun `downscale produces a 512 square`() {
        val out = assertNotNull(IosArtwork.downscale(sourcePng()))
        val image = Image.makeFromEncoded(out)
        assertEquals(512, image.width)
        assertEquals(512, image.height)

        // Inset from the edge so bilinear resampling at the border can't muddy the sample.
        val bitmap = Bitmap.makeFromImage(image)
        val near = 8
        val far = 512 - 1 - near
        assertTrue(channelsClose(bitmap.getColor(near, near), topLeft), "top-left corner")
        assertTrue(channelsClose(bitmap.getColor(far, near), topRight), "top-right corner")
        assertTrue(channelsClose(bitmap.getColor(near, far), bottomLeft), "bottom-left corner")
        assertTrue(channelsClose(bitmap.getColor(far, far), bottomRight), "bottom-right corner")
    }

    @Test
    fun `downscale stays under the widget budget`() {
        val out = assertNotNull(IosArtwork.downscale(sourcePng()))
        assertTrue(out.size <= 400 * 1024, "artwork was ${out.size} bytes, budget is 400 KB")
    }

    @Test
    fun `downscale returns null on garbage rather than throwing`() {
        assertNull(IosArtwork.downscale(byteArrayOf(9, 9, 9)))
    }
}
