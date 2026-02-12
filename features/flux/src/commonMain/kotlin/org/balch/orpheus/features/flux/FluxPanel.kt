package org.balch.orpheus.features.flux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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

private val ScaleNames = listOf("MAJ", "MIN", "PEN", "PHR", "WHO", "CHR")

/**
 * Flux Panel - Controls for the random melody generator.
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

        // Row 1: Main Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Clock Source (Moved to top)
            ValueCycleButton(
                value = state.clockSource,
                values = listOf(0, 1),
                onValueChange = actions.setClockSource,
                labelProvider = { if (it == 0) "INT" else "LFO" },
                label = "CLK",
                modifier = Modifier.align(Alignment.Top),
                color = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.spread,
                onValueChange = actions.setSpread,
                label = "SPREAD",
                controlId = FluxSymbol.SPREAD.controlId.key,
                size = 52.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
            
            RotaryKnob(
                value = state.bias,
                onValueChange = actions.setBias,
                label = "BIAS",
                controlId = FluxSymbol.BIAS.controlId.key,
                size = 52.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
            
            RotaryKnob(
                value = state.steps,
                onValueChange = actions.setSteps,
                label = "STEPS",
                controlId = FluxSymbol.STEPS.controlId.key,
                size = 52.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
            
            RotaryKnob(
                value = state.dejaVu,
                onValueChange = actions.setDejaVu,
                label = "DÉJÀ VU",
                controlId = FluxSymbol.DEJAVU.controlId.key,
                size = 52.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
        }
        
        // Row 2: Length and Scale
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
             // Length Knob (1-16)
            RotaryKnob(
                value = state.length.toFloat(),
                onValueChange = { actions.setLength(it.toInt()) },
                label = "LENGTH",
                range = 1f..16f,
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
            
            // Scale Selector
             RotaryKnob(
                value = state.scaleIndex.toFloat(),
                onValueChange = { actions.setScale(it.toInt()) },
                label = "SCALE",
                range = 0f..5f,
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight,
                valueFormatter = { ScaleNames.getOrElse(it.toInt()) { "" } }
            )
            
            // Rate Divider
            RotaryKnob(
                value = state.rate,
                onValueChange = { actions.setRate(it) },
                label = "RATE",
                controlId = FluxSymbol.RATE.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            // Jitter
            RotaryKnob(
                value = state.jitter,
                onValueChange = actions.setJitter,
                label = "JITTER",
                controlId = FluxSymbol.JITTER.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            // Probability/Gate Bias
            RotaryKnob(
                value = state.probability,
                onValueChange = actions.setProbability,
                label = "PROB",
                controlId = FluxSymbol.PROBABILITY.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            // Gate Length
            RotaryKnob(
                value = state.gateLength,
                onValueChange = { actions.setGateLength(it) },
                label = "GATE",
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
        }
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun FluxPanelPreview() {
    FluxPanel(
        flux = FluxViewModel.previewFeature(),
        isExpanded = true,
        showCollapsedHeader = false
    )
}
