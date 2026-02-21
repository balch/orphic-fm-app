package org.balch.orpheus.features.mediapipe.shader

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader.TileMode
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * Android implementation using AGSL RuntimeShader (API 33+) with input shader
 * for camera image sampling.
 */
actual class CameraEffectRenderer {
    private var runtimeShader: RuntimeShader? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                runtimeShader = RuntimeShader(CameraEffectShader.SKSL_SOURCE)
            } catch (e: Exception) {
                println("[Orpheus] WARNING: Camera effect AGSL shader compilation failed: ${e.message}")
            }
        }
    }

    actual fun isSupported(): Boolean = runtimeShader != null

    actual fun getShaderBrush(
        width: Float,
        height: Float,
        cameraImage: ImageBitmap,
        masterLevel: Float,
        peakLevel: Float,
        lfoMod: Float,
        time: Float,
    ): ShaderBrush? {
        val shader = runtimeShader ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("masterLevel", masterLevel)
        shader.setFloatUniform("peakLevel", peakLevel)
        shader.setFloatUniform("lfoMod", lfoMod)

        // Convert ImageBitmap → Android Bitmap → BitmapShader → child input
        val androidBitmap = cameraImage.asAndroidBitmap()
        val bitmapShader = BitmapShader(androidBitmap, TileMode.CLAMP, TileMode.CLAMP)
        shader.setInputShader("cameraImage", bitmapShader)

        return ShaderBrush(shader)
    }

    actual fun dispose() {
        runtimeShader = null
    }
}
