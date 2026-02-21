package org.balch.orpheus.features.mediapipe.shader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush

/**
 * Platform-specific audio-reactive camera shader renderer.
 *
 * Each platform implements this using available shader APIs:
 * - JVM/Desktop: Skiko RuntimeShaderBuilder with child shader
 * - Android 13+: AGSL RuntimeShader with input shader
 * - wasmJs: Skiko RuntimeShaderBuilder (same as JVM)
 */
expect class CameraEffectRenderer() {
    fun isSupported(): Boolean
    fun getShaderBrush(
        width: Float,
        height: Float,
        cameraImage: ImageBitmap,
        masterLevel: Float,
        peakLevel: Float,
        lfoMod: Float,
        time: Float,
    ): ShaderBrush?
    fun dispose()
}
