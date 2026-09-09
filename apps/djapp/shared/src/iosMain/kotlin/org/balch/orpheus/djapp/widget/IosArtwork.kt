package org.balch.orpheus.djapp.widget

import com.diamondedge.logging.logging
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * Shrinks album art for the widget process. The metadata producer emits
 * 1080x1080 PNGs of 1-3 MB; a widget extension has roughly a 30 MB budget, so
 * the full-size image can never cross the process boundary.
 */
object IosArtwork {

    private val log = logging("IosArtwork")

    /** Null rather than throwing: a bad image must not crash the write path.
     *  [edge] assumes a square source — [Canvas.drawImageRect] scales x and y
     *  independently, so a non-square input is silently stretched. */
    fun downscale(png: ByteArray, edge: Int = 512): ByteArray? = runCatching {
        val source = Image.makeFromEncoded(png)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(edge, edge))
        val canvas = Canvas(bitmap)
        canvas.drawImageRect(
            source,
            Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            Rect.makeWH(edge.toFloat(), edge.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
        Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)?.bytes
    }.onFailure { log.warn(it) { "artwork downscale failed" } }.getOrNull()
}
