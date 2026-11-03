package org.balch.orpheus.features.visualizations.viz.shader

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/** API 33+, where `createRuntimeShaderEffect` first exists; older versions report unsupported. */
actual class LayerShader actual constructor(source: String, private val contentName: String) {
    private var shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader(source)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

    actual val isSupported: Boolean get() = shader != null

    actual fun uniform(name: String, value: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader?.setFloatUniform(name, value)
    }

    actual fun uniform(name: String, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader?.setFloatUniform(name, x, y)
    }

    actual fun uniform(name: String, values: FloatArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader?.setFloatUniform(name, values)
    }

    actual fun effect(): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val s = shader ?: return null
        return try {
            android.graphics.RenderEffect.createRuntimeShaderEffect(s, contentName).asComposeRenderEffect()
        } catch (e: Exception) {
            dispose()
            null
        }
    }

    actual fun dispose() {
        shader = null
    }
}
