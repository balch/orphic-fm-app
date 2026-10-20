package org.balch.orpheus.features.visualizations.viz.shader

import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

actual class SwirlyRenderer {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null

    init {
        try {
            effect = RuntimeEffect.makeForShader(SwirlyShaderSource.SKSL)
            builder = effect?.let { RuntimeShaderBuilder(it) }
        } catch (e: Exception) {
            // brush() returns null and the caller strokes paths instead.
        }
    }

    actual fun brush(width: Float, height: Float, arms: SwirlyArms): ShaderBrush? {
        val b = builder ?: return null
        b.uniform("resolution", width, height)
        b.uniform("time", arms.time)
        b.uniform("lfo", arms.lfo)
        b.uniform("sweep", arms.sweep)
        b.uniform("armColor", arms.colors)
        b.uniform("armWidth", arms.widths)
        return ShaderBrush(b.makeShader().asComposeShader())
    }

    actual fun dispose() {
        builder = null
        effect?.close()
        effect = null
    }
}
