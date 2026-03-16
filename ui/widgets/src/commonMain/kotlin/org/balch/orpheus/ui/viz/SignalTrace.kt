package org.balch.orpheus.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * When true, panel-level SignalTraces are visible.
 * Set by the visualization system when Signal Monitor is the active viz.
 */
val LocalSignalVizEnabled = staticCompositionLocalOf { false }

/**
 * Oscilloscope-style signal trace drawn on a Canvas.
 * Renders a glowing line path from a FloatArray of samples (-1..+1 range).
 * Designed to sit behind panel knobs at low opacity.
 *
 * @param data Signal samples (-1..+1). Empty array draws nothing.
 * @param color Trace color (alpha controlled separately).
 * @param alpha Opacity of the main trace line.
 * @param strokeWidth Width of the main trace in pixels.
 * @param glowWidth Width of the glow halo behind the trace.
 * @param glowAlpha Opacity of the glow halo.
 */
@Composable
fun SignalTrace(
    data: FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 0.15f,
    strokeWidth: Float = 2f,
    glowWidth: Float = 6f,
    glowAlpha: Float = 0.05f,
) {
    val path = remember { Path() }
    val enabled = LocalSignalVizEnabled.current

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!enabled || data.isEmpty()) return@Canvas
        path.reset()
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val step = w / data.size.coerceAtLeast(1)

        data.forEachIndexed { i, v ->
            val x = i * step
            val y = mid - v.coerceIn(-1f, 1f) * mid
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Glow layer (wider, dimmer)
        drawPath(path, color.copy(alpha = glowAlpha), style = Stroke(glowWidth))
        // Main trace
        drawPath(path, color.copy(alpha = alpha), style = Stroke(strokeWidth))
    }
}
