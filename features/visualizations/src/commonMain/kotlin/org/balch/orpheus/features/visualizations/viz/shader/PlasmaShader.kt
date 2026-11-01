package org.balch.orpheus.features.visualizations.viz.shader

import androidx.compose.ui.graphics.ShaderBrush

/**
 * A runtime shader painted as a brush: SkSL through skiko (JVM, WASM, iOS), AGSL on Android 13+.
 * [isSupported] is false where neither exists or the source fails to compile, and the caller
 * draws its Canvas fallback instead. Set uniforms and images, then take [brush], once a frame.
 */
expect class PlasmaShader(source: String) {
    val isSupported: Boolean

    fun uniform(name: String, value: Float)
    fun uniform(name: String, x: Float, y: Float)
    fun uniform(name: String, values: FloatArray)

    /**
     * Binds [pixels], one byte a pixel, [width] x [height] rows top first, as the child shader
     * [name]. The shader reads it as `.a`, filtered linearly, repeating across and clamped down.
     * Alpha is never colour-managed, so the bytes reach the shader as written.
     */
    fun alphaImage(name: String, pixels: ByteArray, width: Int, height: Int)

    fun brush(): ShaderBrush?

    fun dispose()
}
