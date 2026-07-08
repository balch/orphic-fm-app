package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization

/**
 * Spectrograph — real-time master-mix spectrum analyzer. Frequency-hued neon bars
 * (bass magenta → treble cyan) lit by loudness, with an additive bloom and a
 * phosphor-persistence afterglow trailing each slow-falling peak. The FFT is
 * computed in C++; this only shapes and draws the bands.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class SpectrumViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "spectrum"
    override val name = "Spectrograph"
    override val color = OrpheusColors.neonMagenta
    override val knob1Label = "DECAY"
    override val knob2Label = "TILT"

    private var _decay = 0.5f
    private var _tilt = 0.5f

    override fun setKnob1(value: Float) { _decay = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _tilt = value.coerceIn(0f, 1f) }
    override fun onActivate() { engine.setSpectrumEnabled(true) }
    override fun onDeactivate() { engine.setSpectrumEnabled(false) }

    @Composable
    override fun Content(modifier: Modifier) {
        val bands by engine.spectrumFlow.collectAsState()
        val n = bands.size
        val ballistics = remember(n) { SpectrumBallistics(n.coerceAtLeast(1)) }

        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(OrpheusColors.fireworksBackground)
            if (n == 0) return@Canvas

            // Shape the latest frame, then advance the ballistics (bar + slow-falling peak).
            val targets = FloatArray(n)
            for (i in 0 until n) {
                targets[i] = magnitudeToHeight(applyTilt(bands[i], i, n, _tilt))
            }
            ballistics.update(targets, _decay)

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
    }
}
