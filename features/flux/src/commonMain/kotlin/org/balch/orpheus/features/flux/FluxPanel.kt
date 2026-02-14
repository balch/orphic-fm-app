package org.balch.orpheus.features.flux

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
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.ValueCycleButton

private val TModelNames = listOf("BERN", "CLST", "DRUM", "IND", "DIV", "3ST", "MRKV")
private val TRangeNames = listOf("1/4x", "1x", "4x")
private val ControlModeNames = listOf("IDENT", "BUMP", "TILT")
private val VoltageRangeNames = listOf("NARROW", "POS", "FULL")
private val ScaleNames = listOf("MAJ", "MIN", "PEN", "PHR", "WHO", "CHR")

/**
 * Flux Panel - Controls for the random melody generator.
 *
 * Layout: 3 rows — switches on top, knobs grouped by importance:
 * Row 1 (Switches): CLK, T MODEL, T RANGE  |  SCALE, MODE, V RANGE
 * Row 2 (Primary):  SPREAD, BIAS, RATE, DÉJÀ VU, PROB
 * Row 3 (Secondary): STEPS, JITTER, LENGTH, PW, PW RND  ...  MIX
 */
@Composable
fun FluxPanel(
    flux: FluxFeature,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    CollapsibleColumnPanel(
        modifier = modifier,
        title = "FLUX",
        color = OrpheusColors.metallicBlueLight,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        expandedTitle = "Warbles",
        showCollapsedHeader = showCollapsedHeader,
    ) {
        val state by flux.stateFlow.collectAsState()
        val actions = flux.actions

        // Row 1: All switches — T-section left, X-section right
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ValueCycleButton(
                value = state.clockSource,
                values = listOf(0, 1),
                onValueChange = actions.setClockSource,
                labelProvider = { if (it == 0) "INT" else "LFO" },
                label = "CLK",
                color = OrpheusColors.metallicBlueLight
            )

            ValueCycleButton(
                value = state.tModel,
                values = TModelNames.indices.toList(),
                onValueChange = actions.setTModel,
                labelProvider = { TModelNames[it] },
                label = "T MODEL",
                color = OrpheusColors.metallicBlueLight
            )

            ValueCycleButton(
                value = state.tRange,
                values = TRangeNames.indices.toList(),
                onValueChange = actions.setTRange,
                labelProvider = { TRangeNames[it] },
                label = "T RANGE",
                color = OrpheusColors.metallicBlueLight
            )

            Spacer(modifier = Modifier.width(12.dp))

            ValueCycleButton(
                value = state.scaleIndex,
                values = ScaleNames.indices.toList(),
                onValueChange = actions.setScale,
                labelProvider = { ScaleNames[it] },
                label = "SCALE",
                color = OrpheusColors.metallicBlueLight
            )

            ValueCycleButton(
                value = state.controlMode,
                values = ControlModeNames.indices.toList(),
                onValueChange = actions.setControlMode,
                labelProvider = { ControlModeNames[it] },
                label = "MODE",
                color = OrpheusColors.metallicBlueLight
            )

            ValueCycleButton(
                value = state.voltageRange,
                values = VoltageRangeNames.indices.toList(),
                onValueChange = actions.setVoltageRange,
                labelProvider = { VoltageRangeNames[it] },
                label = "V RANGE",
                color = OrpheusColors.metallicBlueLight
            )
        }

        // Row 2: Primary knobs — the controls you reach for most
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {

            RotaryKnob(
                value = state.steps,
                onValueChange = actions.setSteps,
                label = "STEPS",
                controlId = FluxSymbol.STEPS.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.spread,
                onValueChange = actions.setSpread,
                label = "SPREAD",
                controlId = FluxSymbol.SPREAD.controlId.key,
                size = 44.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.bias,
                onValueChange = actions.setBias,
                label = "BIAS",
                controlId = FluxSymbol.BIAS.controlId.key,
                size = 44.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.dejaVu,
                onValueChange = actions.setDejaVu,
                label = "DÉJÀ VU",
                controlId = FluxSymbol.DEJAVU.controlId.key,
                size = 36.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.rate,
                onValueChange = actions.setRate,
                label = "RATE",
                controlId = FluxSymbol.RATE.controlId.key,
                size = 36.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

        }

        // Row 3: Secondary knobs + MIX at bottom-right
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            RotaryKnob(
                value = state.length.toFloat(),
                onValueChange = { actions.setLength(it.toInt()) },
                label = "LENGTH",
                range = 1f..16f,
                size = 30.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.probability,
                onValueChange = actions.setProbability,
                label = "PROB",
                controlId = FluxSymbol.PROBABILITY.controlId.key,
                size = 30.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.jitter,
                onValueChange = actions.setJitter,
                label = "JITTER",
                controlId = FluxSymbol.JITTER.controlId.key,
                size = 30.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.pulseWidth,
                onValueChange = actions.setPulseWidth,
                label = "PW",
                controlId = FluxSymbol.PULSE_WIDTH.controlId.key,
                size = 26.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.pulseWidthStd,
                onValueChange = actions.setPulseWidthStd,
                label = "PW RND",
                controlId = FluxSymbol.PULSE_WIDTH_STD.controlId.key,
                size = 26.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            Spacer(modifier = Modifier.width(4.dp))

            RotaryKnob(
                value = state.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                controlId = FluxSymbol.MIX.controlId.key,
                size = 30.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
        }
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 550, heightDp = 300)
@Composable
private fun FluxPanelPreview() {
    FluxPanel(
        flux = FluxViewModel.previewFeature(),
        isExpanded = true,
        showCollapsedHeader = false
    )
}
