package org.balch.orpheus.features.mediapipe

import androidx.compose.ui.graphics.ImageBitmap
import org.balch.orpheus.core.mediapipe.CameraFrame

/**
 * Converts a CameraFrame (ARGB_8888 ByteArray) to a Compose ImageBitmap.
 * Platform-specific implementations handle the actual pixel buffer conversion.
 */
expect fun CameraFrame.toImageBitmap(): ImageBitmap
