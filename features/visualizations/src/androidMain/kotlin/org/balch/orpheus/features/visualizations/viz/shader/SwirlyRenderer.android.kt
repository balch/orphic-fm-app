package org.balch.orpheus.features.visualizations.viz.shader

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.ShaderBrush

/** AGSL on API 33+; below that the caller strokes paths. */
actual class SwirlyRenderer {
    private var shader: RuntimeShader? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                shader = RuntimeShader(SwirlyShaderSource.SKSL)
            } catch (e: Exception) {
                // brush() returns null and the caller strokes paths instead.
            }
        }
    }

    actual fun brush(width: Float, height: Float, arms: SwirlyArms): ShaderBrush? {
        val s = shader ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        s.setFloatUniform("resolution", width, height)
        s.setFloatUniform("time", arms.time)
        s.setFloatUniform("lfo", arms.lfo)
        s.setFloatUniform("sweep", arms.sweep)
        s.setFloatUniform("armColor", arms.colors)
        s.setFloatUniform("armWidth", arms.widths)
        return ShaderBrush(s)
    }

    actual fun dispose() {
        shader = null
    }
}
