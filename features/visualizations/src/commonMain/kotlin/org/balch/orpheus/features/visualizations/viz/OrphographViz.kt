package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaShader
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaSksl
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization

/**
 * Orphograph — real-time master-mix spectrum analyzer. Each band is a glossy plasma column
 * coloured by pitch that melts into its neighbours, and each slow-falling peak a droplet above
 * it, over the same dark nebula as the Orphogram. Where runtime shaders don't run, the bands
 * draw as neon bars with an additive bloom and a phosphor afterglow trailing each peak. The FFT
 * is computed in C++; this only shapes the bands.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class OrphographViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "spectrum"
    override val name = "Orphograph"
    override val color = OrpheusColors.neonMagenta
    override val knob1Label = "DECAY"
    override val knob2Label = "TILT"
    override val liquidEffects = Default

    private var _decay = 0.5f
    private var _tilt = 0.5f

    override fun setKnob1(value: Float) { _decay = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _tilt = value.coerceIn(0f, 1f) }
    override fun onActivate() { engine.setSpectrumEnabled(true) }
    override fun onDeactivate() { engine.setSpectrumEnabled(false) }

    @Composable
    override fun Content(modifier: Modifier) {
        val bands by engine.spectrumFlow.collectAsStateWithLifecycle()
        val n = bands.size
        val ballistics = remember(n) { SpectrumBallistics(n.coerceAtLeast(1)) }
        val shader = remember { PlasmaShader(PlasmaSksl.ORPHOGRAPH) }
        DisposableEffect(shader) { onDispose { shader.dispose() } }
        var time by remember { mutableFloatStateOf(0f) }
        if (shader.isSupported) {
            // The nebula drifts and the stars twinkle between spectrum frames too.
            LaunchedEffect(Unit) {
                var last = 0L
                while (true) withFrameNanos { now ->
                    if (last != 0L) time += ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = now
                }
            }
        }
        val heights = remember { FloatArray(PlasmaSksl.BANDS) }
        val peaks = remember { FloatArray(PlasmaSksl.BANDS) }

        Canvas(modifier = modifier.fillMaxSize()) {
            if (n > 0) {
                // Shape the latest frame, then advance the ballistics (bar + slow-falling peak).
                val targets = FloatArray(n)
                for (i in 0 until n) {
                    targets[i] = magnitudeToHeight(applyTilt(bands[i], i, n, _tilt))
                }
                ballistics.update(targets, _decay)
            }
            if (shader.isSupported) {
                for (i in 0 until PlasmaSksl.BANDS) {
                    heights[i] = if (i < n) ballistics.heights[i] else 0f
                    peaks[i] = if (i < n) ballistics.peaks[i] else 0f
                }
                shader.uniform("resolution", size.width, size.height)
                shader.uniform("time", time)
                shader.uniform("bands", heights)
                shader.uniform("peaks", peaks)
                shader.brush()?.let { drawRect(it) }
            } else {
                drawBars(ballistics, n)
            }
        }
    }

    private fun DrawScope.drawBars(ballistics: SpectrumBallistics, n: Int) {
        drawRect(OrpheusColors.fireworksBackground)
        if (n == 0) return
        val w = size.width
        val h = size.height
        val slot = w / n
        val barW = slot * 0.7f
        val denom = (n - 1).coerceAtLeast(1)

        for (i in 0 until n) {
            val amp = ballistics.heights[i]
            val bh = amp * h
            val barTopY = h - bh
            val centerX = i * slot + slot / 2f
            val left = centerX - barW / 2f

            // Hue by frequency (bass magenta 320° → treble cyan 180°);
            // saturation + brightness lifted by loudness. (endpoints tunable)
            val hue = 320f - 140f * (i.toFloat() / denom)
            val barColor = Color.hsv(hue, 0.70f + 0.30f * amp, 0.55f + 0.45f * amp)

            // Phosphor afterglow: fading additive trail from the live top up to the
            // lingering (slow-falling) peak — a comet tail on transients.
            val peakY = h - ballistics.peaks[i] * h
            if (barTopY - peakY > 0.5f) {
                val trail = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, barColor.copy(alpha = 0.45f)),
                    startY = peakY,
                    endY = barTopY,
                )
                drawRect(trail, Offset(left, peakY), Size(barW, barTopY - peakY), blendMode = BlendMode.Plus)
            }

            // Additive bloom: wide faint halo → medium → crisp core (overlaps blend into hotspots).
            drawRect(barColor.copy(alpha = 0.10f), Offset(centerX - barW * 0.9f, barTopY), Size(barW * 1.8f, bh), blendMode = BlendMode.Plus)
            drawRect(barColor.copy(alpha = 0.20f), Offset(centerX - barW * 0.6f, barTopY), Size(barW * 1.2f, bh), blendMode = BlendMode.Plus)
            drawRect(barColor, Offset(left, barTopY), Size(barW, bh), blendMode = BlendMode.Plus)

            // Hot filament tip, then the peak-hold cap at the top of the trail.
            drawRect(Color.hsv(hue, 0.35f, 1f), Offset(left, barTopY), Size(barW, 3f), blendMode = BlendMode.Plus)
            drawRect(barColor.copy(alpha = 0.9f), Offset(left, peakY), Size(barW, 2f), blendMode = BlendMode.Plus)
        }
    }

    companion object {
        /**
         * The phone's panel glass: light frost and tint, and the columns punched up in contrast behind
         * the controls. The nav bars take [PlasmaBars] and the dock's panels [DockedPanels].
         */
        private val Default = VisualizationLiquidEffects(
            frostSmall = 4f,
            frostMedium = 6f,
            frostLarge = 8f,
            tintAlpha = 0.1f,
            top = VisualizationLiquidScope(
                saturation = .8f,
                contrast = 2f,
                dispersion = .1f,
                refraction = 0.2f,
                curve = .1f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1f,
                contrast = 2f,
                dispersion = .1f,
                refraction = 0.2f,
                curve = .1f,
            ),
            title = PlasmaTitle,
            bars = PlasmaBars,
            docked = DockedPanels,
        )
    }
}
