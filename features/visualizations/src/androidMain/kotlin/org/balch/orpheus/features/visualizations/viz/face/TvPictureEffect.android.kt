package org.balch.orpheus.features.visualizations.viz.face

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import kotlin.math.floor

/**
 * AGSL on API 33+, where `createRuntimeShaderEffect` first exists; below that the picture draws
 * without the pass. The effect snapshots the shader's uniforms, so each distinct frame needs its
 * own; the held one is reused while nothing the shader reads has changed.
 */
actual class TvPictureRenderer {
    private var shader: RuntimeShader? = null

    private var cached: RenderEffect? = null
    private var lastGlass = Rect.Zero
    private var lastGlitch = Float.NaN
    private var lastMono = Float.NaN
    private var lastRoll = Float.NaN

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                shader = RuntimeShader(TvPictureShaderSource.SKSL)
            } catch (e: Exception) {
                // isSupported() goes false and the picture draws without the pass.
            }
        }
    }

    actual fun isSupported(): Boolean = shader != null

    actual fun dispose() {
        cached = null
        shader = null
    }

    actual fun effect(glass: Rect, glitch: Float, mono: Float, time: Float): RenderEffect? {
        val s = shader ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        // Quantised so a decaying glitch reuses the same effect across most frames instead of
        // allocating a fresh one at 60 Hz (see quantiseGlitch).
        val q = quantiseGlitch(glitch)
        val roll = floor(time * TEAR_ROLL_HZ)
        val hit = cached != null && glass == lastGlass && q == lastGlitch &&
            mono == lastMono && roll == lastRoll
        if (hit) return cached

        return try {
            s.setFloatUniform("glass", glass.left, glass.top, glass.right, glass.bottom)
            s.setFloatUniform("glitch", q)
            s.setFloatUniform("mono", mono)
            s.setFloatUniform("time", time)
            cached = android.graphics.RenderEffect
                .createRuntimeShaderEffect(s, "content")
                .asComposeRenderEffect()
            lastGlass = glass; lastGlitch = q; lastMono = mono; lastRoll = roll
            cached
        } catch (e: Exception) {
            cached = null
            null
        }
    }
}
