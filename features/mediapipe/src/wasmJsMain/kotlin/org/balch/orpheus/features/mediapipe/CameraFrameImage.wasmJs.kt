package org.balch.orpheus.features.mediapipe

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.balch.orpheus.core.mediapipe.CameraFrame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

actual fun CameraFrame.toImageBitmap(): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    val bitmap = Bitmap()
    bitmap.allocPixels(info)
    bitmap.installPixels(info, pixels, width * 4)
    return bitmap.asComposeImageBitmap()
}
