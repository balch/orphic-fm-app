package org.balch.orpheus.features.tides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.TidesSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.SignalTrace
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.ValueCycleButton
import kotlin.math.roundToInt

private val RampModeNames = listOf("AD", "LOOP", "AR")
private val OutputModeNames = listOf("GATES", "AMPL", "PHASE", "FREQ")
private val RangeNames = listOf("CTL", "AUDIO")
private val GateSourceNames = listOf("VOICE", "T1", "T2", "T3", "FREE")
private val ClockSourceNames = listOf("INT", "TEMPO")

/** Map a 0–1 knob value to a name from an array, showing "Name+" when between two entries. */
private fun interpolatedName(value: Float, names: Array<String>): String {
    val position = (value * (names.size - 1).toFloat()).coerceIn(0f, (names.size - 1).toFloat())
    val lower = position.toInt().coerceAtMost(names.size - 2)
    val frac = position - lower
    return if (frac in 0.25f..0.75f && lower + 1 < names.size) {
        "${names[lower]}+"
    } else {
        names[position.roundToInt().coerceIn(0, names.size - 1)]
    }
}

// Shape: angular/pointed (0) → linear (0.5) → bowed/curved (1)
private val shapeNames = arrayOf("Spike", "Edge", "Lean", "Angle", "Linear", "Ease", "Bow", "Round", "Bulge")
private fun tidesShapeName(value: Float) = interpolatedName(value, shapeNames)

// Slope: attack/decay balance (0 = fast attack, 0.5 = symmetric, 1 = slow attack)
private val slopeNames = arrayOf("Pluck", "Snap", "Quick", "Rise", "Even", "Swell", "Slow", "Bloom", "Drift")
private fun tidesSlopeName(value: Float) = interpolatedName(value, slopeNames)

// Smoothness: 0 = sharp/stepped, 0.5 = raw, 1 = heavily filtered
private val smoothNames = arrayOf("Crisp", "Sharp", "Clean", "Raw", "Mild", "Soft", "Smooth", "Liquid", "Fog")
private fun tidesSmoothName(value: Float) = interpolatedName(value, smoothNames)

/**
 * Waves (Tides) Panel — function generator with ramp/envelope/LFO outputs.
 *
 * Layout:
 *   Row 1 (Selectors): Ramp Mode, Output Mode, Range, Gate Source, Clock Source
 *   Row 2 (Primary knobs): Frequency, Slope, Shape, Smoothness
 *   Row 3 (Secondary knobs): Shift, Mix, Clock Offset
 */
@Composable
fun TidesPanel(
    tides: TidesFeature = TidesViewModel.feature(),
    vizCh0Flow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    vizCh1Flow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    vizCh2Flow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    vizCh3Flow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val state by tides.stateFlow.collectAsState()
    val actions = tides.actions

    CollapsibleColumnPanel(
        modifier = modifier,
        title = "Waves",
        expandedTitle = "Gravity",
        color = OrpheusColors.neonOrange,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        showCollapsedHeader = showCollapsedHeader,
        backgroundContent = {
            SignalTrace(data = vizCh3Flow, color = OrpheusColors.neonMagenta, alpha = 0.25f)
            SignalTrace(data = vizCh2Flow, color = OrpheusColors.neonCyan, alpha = 0.25f)
            SignalTrace(data = vizCh1Flow, color = OrpheusColors.warmGlow, alpha = 0.25f)
            SignalTrace(data = vizCh0Flow, color = OrpheusColors.neonOrange, alpha = 0.25f)
        },
    ) {
        // Row 1: Selector buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ValueCycleButton(
                value = state.rampMode,
                values = RampModeNames.indices.toList(),
                onValueChange = actions.setRampMode,
                labelProvider = { RampModeNames[it] },
                label = "MODE",
                color = OrpheusColors.neonOrange
            )

            ValueCycleButton(
                value = state.outputMode,
                values = OutputModeNames.indices.toList(),
                onValueChange = actions.setOutputMode,
                labelProvider = { OutputModeNames[it] },
                label = "OUT",
                color = OrpheusColors.neonOrange
            )

            ValueCycleButton(
                value = state.range,
                values = RangeNames.indices.toList(),
                onValueChange = actions.setRange,
                labelProvider = { RangeNames[it] },
                label = "RANGE",
                color = OrpheusColors.neonOrange
            )

            Spacer(modifier = Modifier.width(8.dp))

            ValueCycleButton(
                value = state.gateSource,
                values = GateSourceNames.indices.toList(),
                onValueChange = actions.setGateSource,
                labelProvider = { GateSourceNames[it] },
                label = "GATE",
                color = OrpheusColors.neonOrange
            )

            ValueCycleButton(
                value = state.clockSource,
                values = ClockSourceNames.indices.toList(),
                onValueChange = actions.setClockSource,
                labelProvider = { ClockSourceNames[it] },
                label = "CLK",
                color = OrpheusColors.neonOrange
            )
        }

        // Row 2: Primary knobs
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            RotaryKnob(
                value = state.frequency,
                onValueChange = actions.setFrequency,
                label = "FREQ",
                controlId = TidesSymbol.FREQUENCY.controlId.key,
                size = 56.dp,
                progressColor = OrpheusColors.neonOrange
            )
            RotaryKnob(
                value = state.slope,
                onValueChange = actions.setSlope,
                label = "SLOPE",
                controlId = TidesSymbol.SLOPE.controlId.key,
                size = 52.dp,
                progressColor = OrpheusColors.neonOrange,
                valueFormatter = ::tidesSlopeName
            )
            RotaryKnob(
                value = state.shape,
                onValueChange = actions.setShape,
                label = "SHAPE",
                controlId = TidesSymbol.SHAPE.controlId.key,
                size = 52.dp,
                progressColor = OrpheusColors.neonOrange,
                valueFormatter = ::tidesShapeName
            )
            RotaryKnob(
                value = state.smoothness,
                onValueChange = actions.setSmoothness,
                label = "SMOOTH",
                controlId = TidesSymbol.SMOOTHNESS.controlId.key,
                size = 52.dp,
                progressColor = OrpheusColors.neonOrange,
                valueFormatter = ::tidesSmoothName
            )
        }

        // Row 3: Secondary knobs
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            RotaryKnob(
                value = state.shift,
                onValueChange = actions.setShift,
                label = "SHIFT",
                controlId = TidesSymbol.SHIFT.controlId.key,
                size = 44.dp,
                progressColor = OrpheusColors.neonOrange
            )
            RotaryKnob(
                value = state.clockOffset,
                onValueChange = actions.setClockOffset,
                label = "CLK OFS",
                controlId = TidesSymbol.CLOCK_OFFSET.controlId.key,
                size = 44.dp,
                progressColor = OrpheusColors.neonOrange
            )
            RotaryKnob(
                value = state.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                controlId = TidesSymbol.MIX.controlId.key,
                size = 44.dp,
                progressColor = OrpheusColors.neonOrange
            )
        }
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 500, heightDp = 280)
@Composable
private fun TidesPanelPreview() {
    TidesPanel(
        tides = TidesViewModel.previewFeature(),
        isExpanded = true,
        showCollapsedHeader = false
    )
}
