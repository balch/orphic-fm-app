package org.balch.orpheus.features.visualizations.viz.shader

import androidx.compose.ui.graphics.RenderEffect

/**
 * A runtime shader run over a layer's drawn content, read through the child shader named
 * [contentName] in the layer's own pixels: a Skia `ImageFilter` through skiko (JVM, WASM, iOS), AGSL
 * `RenderEffect` on Android 13+. [isSupported] is false where neither exists or the shader failed.
 */
expect class LayerShader(source: String, contentName: String) {
    val isSupported: Boolean

    fun uniform(name: String, value: Float)
    fun uniform(name: String, x: Float, y: Float)
    fun uniform(name: String, values: FloatArray)

    /** The pass for the uniforms set so far, which it keeps; later uniforms need a new one. */
    fun effect(): RenderEffect?

    fun dispose()
}
