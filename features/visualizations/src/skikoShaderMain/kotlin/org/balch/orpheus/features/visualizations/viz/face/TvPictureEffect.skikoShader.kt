package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.floor

/**
 * The picture pass on every target that renders through skiko: JVM, WASM and iOS alike.
 *
 * `ImageFilter.makeRuntimeShader` copies the builder's uniforms, so each distinct frame needs its
 * own filter. The filters are not closed here: the layer still holds the one it was last given,
 * and skiko's cleaner frees them once nothing references them.
 */
actual class TvPictureRenderer {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null

    private var cached: RenderEffect? = null
    private var lastGlass = Rect.Zero
    private var lastGlitch = Float.NaN
    private var lastMono = Float.NaN
    private var lastRoll = Float.NaN

    init {
        try {
            effect = RuntimeEffect.makeForShader(TvPictureShaderSource.SKSL)
            builder = effect?.let { RuntimeShaderBuilder(it) }
        } catch (e: Exception) {
            // isSupported() goes false and the picture draws without the pass.
        }
    }

    actual fun isSupported(): Boolean = builder != null

    actual fun dispose() {
        cached = null
        builder?.close()
        builder = null
        effect?.close()
        effect = null
    }

    actual fun effect(glass: Rect, glitch: Float, mono: Float, time: Float): RenderEffect? {
        val b = builder ?: return null
        // Quantised so a decaying glitch reuses the same filter across most frames instead of
        // allocating a fresh native ImageFilter at 60 Hz (see quantiseGlitch).
        val q = quantiseGlitch(glitch)
        val roll = floor(time * TEAR_ROLL_HZ)
        val hit = cached != null && glass == lastGlass && q == lastGlitch &&
            mono == lastMono && roll == lastRoll
        if (hit) return cached

        return try {
            b.uniform("glass", glass.left, glass.top, glass.right, glass.bottom)
            b.uniform("glitch", q)
            b.uniform("mono", mono)
            b.uniform("time", time)
            cached = ImageFilter.makeRuntimeShader(b, "content", null).asComposeRenderEffect()
            lastGlass = glass; lastGlitch = q; lastMono = mono; lastRoll = roll
            cached
        } catch (e: Exception) {
            cached = null
            null
        }
    }
}
