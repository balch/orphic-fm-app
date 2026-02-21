package org.balch.orpheus.features.mediapipe

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.balch.orpheus.core.mediapipe.CameraFrame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image

actual fun CameraFrame.toImageBitmap(): ImageBitmap {
    val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    val image = Image.makeRaster(imageInfo, pixels, width * 4)
    return image.toComposeImageBitmap()
}
