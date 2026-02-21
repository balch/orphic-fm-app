package org.balch.orpheus.features.mediapipe

import androidx.compose.ui.graphics.ImageBitmap
import org.balch.orpheus.core.mediapipe.CameraFrame

/** Stub: camera/MediaPipe not available on wasmJs — returns a 1x1 placeholder. */
actual fun CameraFrame.toImageBitmap(): ImageBitmap = ImageBitmap(1, 1)
