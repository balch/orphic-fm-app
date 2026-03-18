@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.balch.orpheus.features.mediapipe.shader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush

/**
 * iOS stub for CameraEffectRenderer.
 *
 * A full implementation can use Metal shaders via Skiko in the future.
 */
actual class CameraEffectRenderer {
    actual fun isSupported(): Boolean = false

    actual fun getShaderBrush(
        width: Float,
        height: Float,
        cameraImage: ImageBitmap,
        masterLevel: Float,
        peakLevel: Float,
        lfoMod: Float,
        time: Float,
    ): ShaderBrush? = null

    actual fun dispose() {}
}
