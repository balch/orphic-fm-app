package org.balch.orpheus.features.reverb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.SignalTrace
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun ReverbPanel(
    feature: ReverbFeature = ReverbViewModel.feature(),
    inVizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    outVizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
    showExpandedTitle: Boolean = true,
    fillHeight: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "VERB",
        expandedTitle = if (showExpandedTitle) "Echo" else null,
        showCollapsedHeader = showCollapsedHeader,
        fillHeight = fillHeight,
        color = OrpheusColors.echoLavender,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        backgroundContent = {
            SignalTrace(data = inVizFlow, color = OrpheusColors.echoPeriwinkle)
            SignalTrace(data = outVizFlow, color = OrpheusColors.echoLavender)
        }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RotaryKnob(
                    value = uiState.damping,
                    onValueChange = actions.setDamping,
                    label = "DAMP",
                    controlId = ReverbSymbol.DAMPING.controlId.key,
                    size = 48.dp,
                    progressColor = OrpheusColors.echoLavender
                )
                RotaryKnob(
                    value = uiState.diffusion,
                    onValueChange = actions.setDiffusion,
                    label = "DIFF",
                    controlId = ReverbSymbol.DIFFUSION.controlId.key,
                    size = 48.dp,
                    progressColor = OrpheusColors.echoLavender
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RotaryKnob(
                    value = uiState.time,
                    onValueChange = actions.setTime,
                    label = "TIME",
                    controlId = ReverbSymbol.TIME.controlId.key,
                    size = 48.dp,
                    progressColor = OrpheusColors.echoLavender
                )
                RotaryKnob(
                    value = uiState.amount,
                    onValueChange = actions.setAmount,
                    label = "Mix",
                    controlId = ReverbSymbol.AMOUNT.controlId.key,
                    size = 48.dp,
                    progressColor = OrpheusColors.echoLavender
                )
            }
        }
    }
}

@Preview(widthDp = 400, heightDp = 200)
@Preview(widthDp = 400, heightDp = 200, name = "140%", fontScale = 1.4f)
@Composable
fun ReverbPanelPreview() {
    OrpheusTheme {
        ReverbPanel(
            feature = ReverbViewModel.previewFeature(),
            isExpanded = true,
        )
    }
}
