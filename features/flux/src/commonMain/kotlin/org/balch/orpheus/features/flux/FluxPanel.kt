package org.balch.orpheus.features.flux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.ValueCycleButton

private val ScaleNames = listOf("CHR", "MAJ", "MIN", "PEN", "PHR", "WHO")

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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            // Clock Source (Moved to top)
            ValueCycleButton(
                value = state.clockSource,
                values = listOf(0, 1),
                onValueChange = actions.setClockSource,
                labelProvider = { if (it == 0) "INT" else "LFO" },
                modifier = Modifier.width(48.dp).height(24.dp).align(Alignment.CenterVertically),
                color = OrpheusColors.metallicBlueLight
            )

            RotaryKnob(
                value = state.spread,
                onValueChange = actions.setSpread,
                label = "SPREAD",
                controlId = FluxViewModel.SPREAD,
                size = 52.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
            
            RotaryKnob(
                value = state.bias,
                onValueChange = actions.setBias,
                label = "BIAS",
                controlId = FluxViewModel.BIAS,
                size = 52.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
            
            RotaryKnob(
                value = state.steps,
                onValueChange = actions.setSteps,
                label = "STEPS",
                controlId = FluxViewModel.STEPS,
                size = 52.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
            
            RotaryKnob(
                value = state.dejaVu,
                onValueChange = actions.setDejaVu,
                label = "DÉJÀ VU",
                controlId = FluxViewModel.DEJA_VU,
                size = 52.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Row 2: Length and Scale
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
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
                controlId = "flux_rate",
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            // Jitter
            RotaryKnob(
                value = state.jitter,
                onValueChange = { actions.setJitter(it) },
                label = "JITTER",
                controlId = "flux_jitter",
                size = 48.dp,
                progressColor = OrpheusColors.metallicBlueLight
            )

            // Probability/Gate Bias
            RotaryKnob(
                value = state.probability,
                onValueChange = { actions.setProbability(it) },
                label = "PROB",
                controlId = "flux_probability",
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
