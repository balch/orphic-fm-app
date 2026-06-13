package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
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
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
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
        top = VisualizationLiquidScope(
            saturation = 3.0f,
        ),
        bottom = VisualizationLiquidScope(
            saturation = 3.0f,
        ),
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
        Channel("W-CARRIER", OrpheusColors.warpsGreen.copy(alpha = 0.5f)),
        Channel("W-MOD", OrpheusColors.warpsGreen.copy(alpha = 0.7f)),
        Channel("W-OUT", OrpheusColors.warpsGreen),
        Channel("DLY-IN", OrpheusColors.warmGlow.copy(alpha = 0.6f)),
        Channel("DLY-FB", OrpheusColors.warmGlow.copy(alpha = 0.4f)),
        Channel("DLY-OUT", OrpheusColors.warmGlow),
        Channel("REV-IN", OrpheusColors.echoPeriwinkle),
        Channel("REV-OUT", OrpheusColors.echoLavender),
        Channel("FLUX", OrpheusColors.metallicBlueLight),
        Channel("RESO-IN", OrpheusColors.lakersGold.copy(alpha = 0.5f)),
        Channel("RESO-OUT", OrpheusColors.lakersGold),
        Channel("DRUM", OrpheusColors.ninersRed),
        Channel("GRN-IN", OrpheusColors.grainsRed.copy(alpha = 0.5f)),
        Channel("GRN-OUT", OrpheusColors.grainsRed),
        Channel("BASS", OrpheusColors.bassAmber),
        Channel("HORN-IN", OrpheusColors.hornWoofer),
        Channel("HORN-OUT", OrpheusColors.hornCrimson),
        Channel("DJ", OrpheusColors.djRed),
        Channel("MASTER", OrpheusColors.neonMagenta),
        Channel("SYZ-0", OrpheusColors.neonOrange),
        Channel("SYZ-1", OrpheusColors.warmGlow),
        Channel("SYZ-2", OrpheusColors.neonCyan.copy(alpha = 0.7f)),
        Channel("SYZ-3", OrpheusColors.neonMagenta.copy(alpha = 0.7f)),
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
        val drumOutData by engine.drumOutVizFlow.collectAsState()
        val grainsInData by engine.grainsInVizFlow.collectAsState()
        val grainsOutData by engine.grainsOutVizFlow.collectAsState()
        val bassOutData by engine.bassOutVizFlow.collectAsState()
        val hornInData by engine.hornInVizFlow.collectAsState()
        val hornOutData by engine.hornOutVizFlow.collectAsState()
        val djOutData by engine.djOutVizFlow.collectAsState()
        val masterOutData by engine.masterOutVizFlow.collectAsState()
        val tidesCh0Data by engine.tidesCh0VizFlow.collectAsState()
        val tidesCh1Data by engine.tidesCh1VizFlow.collectAsState()
        val tidesCh2Data by engine.tidesCh2VizFlow.collectAsState()
        val tidesCh3Data by engine.tidesCh3VizFlow.collectAsState()

        val allData = listOf(
            lfoData, carrierData, modData, outData,
            delayInData, delayFbData, delayOutData,
            reverbInData, reverbOutData,
            fluxData, resoInData, resoOutData,
            drumOutData, grainsInData, grainsOutData,
            bassOutData,
            hornInData, hornOutData,
            djOutData,
            masterOutData,
            tidesCh0Data, tidesCh1Data, tidesCh2Data, tidesCh3Data,
        )
        val paths = remember { Array(channels.size) { Path() } }

        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val margin = 24f
            val mid = h / 2f
            val amplitude = h * 0.4f  // all traces share full height, centered

            // Dark background
            drawRect(OrpheusColors.fireworksBackground)

            // Center reference line
            drawLine(
                color = Color.White.copy(alpha = 0.04f),
                start = Offset(margin, mid),
                end = Offset(w - margin, mid),
                strokeWidth = 0.5f
            )

            // Zoom: 0 = full 5sec history, 1 = last ~50 samples (waveform detail)
            val visibleSamples = (480 * (1f - _zoom * 0.9f)).toInt().coerceAtLeast(30)
            val drawW = w - margin * 2

            // Draw each channel overlaid at center — skip empty (bypassed) channels.
            // Two stroke passes (glow halo + crisp trace) plus an in-place sub-range
            // walk (no per-frame copyOfRange) keep this full-screen scope cheap to
            // record: the old 4-pass + array-copy path dominated UI-thread draw time
            // (see gfxinfo "slow issue draw commands"). HeartbeatViz is the reference
            // for the cheaper read-in-draw-scope style.
            channels.forEachIndexed { idx, channel ->
                val fullData = allData[idx]
                if (fullData.isEmpty()) return@forEachIndexed

                // Zoom = walk only the most recent visibleSamples, in place.
                val startIdx = if (fullData.size > visibleSamples)
                    fullData.size - visibleSamples else 0
                val visibleCount = fullData.size - startIdx

                // Skip silent (bypassed) channels — flat lines add cost, not signal.
                var peak = 0f
                for (i in startIdx until fullData.size) {
                    val a = kotlin.math.abs(fullData[i])
                    if (a > peak) peak = a
                }
                if (peak < 0.001f) return@forEachIndexed

                // Decimate to at most MAX_DRAW_POINTS — at full zoom-out 480 samples
                // tessellate into a needlessly dense stroke the screen can't resolve.
                val stride = (visibleCount / MAX_DRAW_POINTS).coerceAtLeast(1)
                val pointCount = (visibleCount + stride - 1) / stride
                val step = drawW / (pointCount - 1).coerceAtLeast(1)

                val path = paths[idx]
                path.reset()
                var p = 0
                var i = startIdx
                while (i < fullData.size) {
                    val x = margin + p * step
                    val y = mid - fullData[i].coerceIn(-1f, 1f) * amplitude
                    if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    p++
                    i += stride
                }

                // Pass 1 — wide glow halo.
                drawPath(
                    path,
                    channel.color.copy(alpha = 0.04f + _glow * 0.12f),
                    style = Stroke(6f),
                )
                // Pass 2 — crisp main trace.
                drawPath(
                    path,
                    channel.color.copy(alpha = 0.30f + _glow * 0.45f),
                    style = Stroke(1.5f),
                )
            }
        }
    }

    companion object {
        // A full-screen scope can't resolve more than ~96 points across its
        // width, so decimate beyond this to cap stroke-tessellation cost.
        private const val MAX_DRAW_POINTS = 96
    }
}
