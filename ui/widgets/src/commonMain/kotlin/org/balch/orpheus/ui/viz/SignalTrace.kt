package org.balch.orpheus.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.flow.StateFlow

/**
 * When true, panel-level SignalTraces are visible.
 * Set by the visualization system when Signal Monitor is the active viz.
 */
val LocalSignalVizEnabled = staticCompositionLocalOf { false }

/**
 * Glow intensity for panel-level SignalTraces (0..1).
 * Inverse of the main Orphoscope GLOW knob: CCW = bright panels, CW = dim panels.
 */
val LocalSignalVizGlow = staticCompositionLocalOf { 0.5f }

/**
 * Oscilloscope-style signal trace drawn on a Canvas.
 * Renders a glowing line path from a FloatArray of samples (-1..+1 range).
 * Designed to sit behind panel knobs at low opacity.
 *
 * Takes the sample [StateFlow] (not a materialized array) and collects it
 * internally. That keeps the high-frequency viz subscription in this leaf
 * Canvas, so a ~30Hz emission only redraws the trace — the host panel and its
 * knobs never recompose. When the scope is off the flow is cleared upstream and
 * emits nothing, so a disabled trace costs nothing.
 *
 * @param data Signal-sample flow (-1..+1). Empty array draws nothing.
 * @param color Trace color (alpha controlled separately).
 * @param alpha Opacity of the main trace line.
 * @param strokeWidth Width of the main trace in pixels.
 * @param glowWidth Width of the glow halo behind the trace.
 * @param glowAlpha Opacity of the glow halo.
 */
@Composable
fun SignalTrace(
    data: StateFlow<FloatArray>,
    color: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 0.15f,
    strokeWidth: Float = 2f,
    glowWidth: Float = 6f,
    glowAlpha: Float = 0.05f,
) {
    val path = remember { Path() }
    val enabled = LocalSignalVizEnabled.current
    val glow = LocalSignalVizGlow.current
    val samples by data.collectAsState()

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!enabled || samples.isEmpty()) return@Canvas
        path.reset()
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val step = w / samples.size.coerceAtLeast(1)

        samples.forEachIndexed { i, v ->
            val x = i * step
            val y = mid - v.coerceIn(-1f, 1f) * mid
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Scale alpha by panel glow (0..1 from LocalSignalVizGlow), 1.5x base boost
        val effectiveGlowAlpha = glowAlpha * 1.5f * (0.5f + glow)
        val effectiveAlpha = alpha * 1.5f * (0.5f + glow)

        // Glow layer (wider, dimmer)
        drawPath(path, color.copy(alpha = effectiveGlowAlpha), style = Stroke(glowWidth))
        // Main trace
        drawPath(path, color.copy(alpha = effectiveAlpha), style = Stroke(strokeWidth))
    }
}
