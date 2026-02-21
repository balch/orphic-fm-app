package org.balch.orpheus.features.mediapipe

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.balch.orpheus.core.mediapipe.CameraFrame
import java.nio.ByteBuffer

actual fun CameraFrame.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    return bitmap.asImageBitmap()
}
