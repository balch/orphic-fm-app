package org.balch.orpheus.features.visualizations.viz.shader

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** The filters are not closed here: the layer holds the last one, and skiko's cleaner frees them. */
actual class LayerShader actual constructor(source: String, private val contentName: String) {
    private var runtimeEffect: RuntimeEffect? = try {
        RuntimeEffect.makeForShader(source)
    } catch (e: Exception) {
        null // isSupported goes false and the caller draws its fallback
    }
    private var builder: RuntimeShaderBuilder? = runtimeEffect?.let { RuntimeShaderBuilder(it) }

    actual val isSupported: Boolean get() = builder != null

    actual fun uniform(name: String, value: Float) { builder?.uniform(name, value) }
    actual fun uniform(name: String, x: Float, y: Float) { builder?.uniform(name, x, y) }
    actual fun uniform(name: String, values: FloatArray) { builder?.uniform(name, values) }

    actual fun effect(): RenderEffect? {
        val b = builder ?: return null
        return try {
            ImageFilter.makeRuntimeShader(b, contentName, null).asComposeRenderEffect()
        } catch (e: Exception) {
            dispose()
            null
        }
    }

    actual fun dispose() {
        builder?.close()
        builder = null
        runtimeEffect?.close()
        runtimeEffect = null
    }
}
