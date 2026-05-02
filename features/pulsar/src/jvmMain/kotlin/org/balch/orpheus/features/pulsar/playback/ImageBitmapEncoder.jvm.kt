package org.balch.orpheus.features.pulsar.playback

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun ImageBitmap.toPngBytes(): ByteArray? {
    val skiaImage = Image.makeFromBitmap(asSkiaBitmap())
    return skiaImage.encodeToData(EncodedImageFormat.PNG)?.bytes
}
