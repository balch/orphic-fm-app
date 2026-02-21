package org.balch.orpheus.features.mediapipe.shader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * wasmJs implementation using Skiko's RuntimeShaderBuilder — same as JVM
 * since both use Skiko for shader compilation.
 */
actual class CameraEffectRenderer {
    private var runtimeEffect: RuntimeEffect? = null
    private var shaderBuilder: RuntimeShaderBuilder? = null
    private var cachedSkiaImage: Image? = null

    init {
        try {
            runtimeEffect = RuntimeEffect.makeForShader(CameraEffectShader.SKSL_SOURCE)
            shaderBuilder = runtimeEffect?.let { RuntimeShaderBuilder(it) }
        } catch (e: Exception) {
            println("[Orpheus] WARNING: Camera effect shader compilation failed: ${e.message}")
        }
    }

    actual fun isSupported(): Boolean = shaderBuilder != null

    actual fun getShaderBrush(
        width: Float,
        height: Float,
        cameraImage: ImageBitmap,
        masterLevel: Float,
        peakLevel: Float,
        lfoMod: Float,
        time: Float,
    ): ShaderBrush? {
        val builder = shaderBuilder ?: return null

        builder.uniform("resolution", width, height)
        builder.uniform("time", time)
        builder.uniform("masterLevel", masterLevel)
        builder.uniform("peakLevel", peakLevel)
        builder.uniform("lfoMod", lfoMod)

        cachedSkiaImage?.close()
        val skiaBitmap = cameraImage.asSkiaBitmap()
        val skiaImage = Image.makeFromBitmap(skiaBitmap)
        cachedSkiaImage = skiaImage
        val imageShader = skiaImage.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP)
        builder.child("cameraImage", imageShader)

        val shader = builder.makeShader()
        return ShaderBrush(shader)
    }

    actual fun dispose() {
        cachedSkiaImage?.close()
        cachedSkiaImage = null
        shaderBuilder = null
        runtimeEffect?.close()
        runtimeEffect = null
    }
}
