package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.visualizations.viz.spectrogram.OrphogramCanvas
import org.balch.orpheus.ui.infrastructure.LabelTone
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Orphogram: the master mix as a scrolling log-frequency picture, 50 Hz to 10 kHz, low at the
 * bottom, newest at the right. Loud regions melt into glossy plasma coloured by pitch over a
 * dark nebula; RANGE dB under a slowly falling peak sets what counts as loud. The FFT runs in
 * C++; this writes one column a frame and the shader does the rest.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class OrphogramViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "spectrogram"
    override val name = "Orphogram"
    override val color = Color(0xFF5A28C8)
    override val knob1Label = "SPEED"
    override val knob2Label = "RANGE"
    override val liquidEffects = Default
    override val knob2ValueFormatter: (Float) -> String = { "${rangeDb(it).roundToInt()} dB" }

    private var _speed = 0.5f
    private var _range = 0.5f

    override fun setKnob1(value: Float) { _speed = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _range = value.coerceIn(0f, 1f) }
    override fun onActivate() { engine.setSpectrogramEnabled(true) }
    override fun onDeactivate() { engine.setSpectrogramEnabled(false) }

    /** Seconds across the screen: 24 s at the slowest, 3 s at the fastest, 8.5 s in the middle. */
    private fun spanSeconds(speed: Float): Float = 24f * 0.125f.pow(speed)

    /** 40 dB (punchy, only the loud partials) to 100 dB (every tail and the noise floor); 70 in the middle. */
    private fun rangeDb(range: Float): Float = 40f + 60f * range

    @Composable
    override fun Content(modifier: Modifier) {
        OrphogramCanvas(
            modifier = modifier.fillMaxSize(),
            spanSeconds = { spanSeconds(_speed) },
            rangeDb = { rangeDb(_range) },
            source = { engine.spectrogramFlow.value },
        )
    }

    companion object {
        /**
         * The phone's panel glass: light frost and tint, no lens bend, and a lift in colour and
         * contrast, so the plasma reads undistorted through the controls. The nav bars take
         * [PlasmaBars] and the dock's panels [DockedPanels].
         */
        private val Default = VisualizationLiquidEffects(
            frostSmall = 2f,
            frostMedium = 3f,
            frostLarge = 4f,
            tintAlpha = 0.06f,
            top = VisualizationLiquidScope(saturation = 1.25f, contrast = 1.15f),
            bottom = VisualizationLiquidScope(saturation = 1.25f, contrast = 1.15f),
            // The plasma is the panels' own purple, so their labels go light to read over it.
            labelTone = LabelTone.Light,
            title = PlasmaTitle,
            bars = PlasmaBars,
            // The dock's heavy frost turned the plasma pale lilac under every label, so its glass
            // fills with the void's dark instead and the labels go light.
            docked = DockedPanels.copy(
                tintColor = OrpheusColors.darkVoid,
                tintAlpha = 0.55f,
                labelTone = LabelTone.Light,
            ),
        )
    }
}

