package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization

/**
 * Signal Monitor — master oscilloscope showing all viz channels.
 * Glowing traces on a dark background, low frost for maximum clarity.
 */
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<Visualization>())
class SignalMonitorViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "signal-monitor"
    override val name = "Orphoscope"
    override val color = OrpheusColors.neonCyan
    override val knob1Label = "ZOOM"
    override val knob2Label = "GLOW"

    // Low frost, high see-through — signals must be crisp
    override val liquidEffects = VisualizationLiquidEffects(
        frostSmall = 1f,
        frostMedium = 2f,
        frostLarge = 3f,
        tintAlpha = 0.01f,
        top = VisualizationLiquidScope(),
        bottom = VisualizationLiquidScope(),
        title = CenterPanelStyle(
            scope = VisualizationLiquidScope(
                refraction = .75f,
                curve = .05f,
                saturation = 3f,
                dispersion = 2f,
            ),
            titleColor = OrpheusColors.neonCyan,
            borderColor = OrpheusColors.neonCyan.copy(alpha = 0.3f),
            titleElevation = 4.dp
        )
    )

    private var _zoom = 0.5f
    private var _glow = 0.5f

    override fun setKnob1(value: Float) { _zoom = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _glow = value.coerceIn(0f, 1f) }
    override fun onActivate() {}
    override fun onDeactivate() {}

    private data class Channel(
        val name: String,
        val color: Color,
    )

    private val channels = listOf(
        Channel("LFO", OrpheusColors.neonCyan),
        Channel("W-CARRIER", Color(0xFF4488FF)),
        Channel("W-MOD", Color(0xFF00FF88)),
        Channel("W-OUT", Color(0xFFFF8844)),
        Channel("DLY-IN", Color(0xFF6688CC)),
        Channel("DLY-FB", Color(0xFFCC6688)),
        Channel("DLY-OUT", Color(0xFF88CC66)),
        Channel("REV-IN", Color(0xFF8866CC)),
        Channel("REV-OUT", Color(0xFFCC88FF)),
        Channel("FLUX", Color(0xFFFFCC44)),
        Channel("RESO-IN", Color(0xFF44CCCC)),
        Channel("RESO-OUT", Color(0xFFCC4488)),
    )

    @Composable
    override fun Content(modifier: Modifier) {
        val lfoData by engine.lfoVizFlow.collectAsState()
        val carrierData by engine.warpsCarrierVizFlow.collectAsState()
        val modData by engine.warpsModVizFlow.collectAsState()
        val outData by engine.warpsOutVizFlow.collectAsState()
        val delayInData by engine.delayInVizFlow.collectAsState()
        val delayFbData by engine.delayFbVizFlow.collectAsState()
        val delayOutData by engine.delayOutVizFlow.collectAsState()
        val reverbInData by engine.reverbInVizFlow.collectAsState()
        val reverbOutData by engine.reverbOutVizFlow.collectAsState()
        val fluxData by engine.fluxCvVizFlow.collectAsState()
        val resoInData by engine.resoInVizFlow.collectAsState()
        val resoOutData by engine.resoOutVizFlow.collectAsState()

        val allData = listOf(
            lfoData, carrierData, modData, outData,
            delayInData, delayFbData, delayOutData,
            reverbInData, reverbOutData,
            fluxData, resoInData, resoOutData
        )
        val paths = remember { Array(channels.size) { Path() } }
        val textMeasurer = rememberTextMeasurer()

        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val margin = 24f
            val mid = h / 2f
            val amplitude = h * 0.4f  // all traces share full height, centered

            // Dark background
            drawRect(Color(0xFF050510))

            // Center reference line
            drawLine(
                color = Color.White.copy(alpha = 0.04f),
                start = Offset(margin, mid),
                end = Offset(w - margin, mid),
                strokeWidth = 0.5f
            )

            // Zoom: 0 = full 5sec history, 1 = last ~50 samples (waveform detail)
            val visibleSamples = (480 * (1f - _zoom * 0.9f)).toInt().coerceAtLeast(30)

            // Draw each channel overlaid at center — skip empty (bypassed) channels
            channels.forEachIndexed { idx, channel ->
                val fullData = allData[idx]
                if (fullData.isEmpty()) return@forEachIndexed
                // Apply zoom — show only the most recent visibleSamples
                val data = if (fullData.size > visibleSamples) {
                    fullData.copyOfRange(fullData.size - visibleSamples, fullData.size)
                } else fullData

                // Check if signal is silent (bypassed) — skip flat lines
                var peak = 0f
                for (v in data) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
                if (peak < 0.001f) return@forEachIndexed

                val path = paths[idx]
                path.reset()
                val drawW = w - margin * 2
                val step = drawW / data.size.coerceAtLeast(1)

                data.forEachIndexed { i, v ->
                    val x = margin + i * step
                    val y = mid - v.coerceIn(-1f, 1f) * amplitude
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                // Wide glow halo
                val glowAlpha = 0.03f + _glow * 0.10f
                drawPath(path, channel.color.copy(alpha = glowAlpha), style = Stroke(8f))

                // Medium glow
                val midGlowAlpha = 0.06f + _glow * 0.12f
                drawPath(path, channel.color.copy(alpha = midGlowAlpha), style = Stroke(3f))

                // Crisp main trace
                val traceAlpha = 0.25f + _glow * 0.45f
                drawPath(path, channel.color.copy(alpha = traceAlpha), style = Stroke(1.5f))

                // Additive bloom core
                drawPath(
                    path,
                    channel.color.copy(alpha = 0.08f + _glow * 0.15f),
                    style = Stroke(0.75f),
                    blendMode = BlendMode.Plus
                )
            }
        }
    }
}
