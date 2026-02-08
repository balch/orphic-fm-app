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
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun ReverbPanel(
    feature: ReverbFeature = ReverbViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "VERB",
        expandedTitle = "Echo",
        color = OrpheusColors.echoLavender,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
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
                    value = uiState.amount,
                    onValueChange = actions.setAmount,
                    label = "AMT",
                    controlId = ReverbSymbol.AMOUNT.controlId.key,
                    size = 40.dp,
                    progressColor = OrpheusColors.echoLavender
                )
                RotaryKnob(
                    value = uiState.time,
                    onValueChange = actions.setTime,
                    label = "TIME",
                    controlId = ReverbSymbol.TIME.controlId.key,
                    size = 40.dp,
                    progressColor = OrpheusColors.echoLavender
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RotaryKnob(
                    value = uiState.damping,
                    onValueChange = actions.setDamping,
                    label = "DAMP",
                    controlId = ReverbSymbol.DAMPING.controlId.key,
                    size = 40.dp,
                    progressColor = OrpheusColors.echoLavender
                )
                RotaryKnob(
                    value = uiState.diffusion,
                    onValueChange = actions.setDiffusion,
                    label = "DIFF",
                    controlId = ReverbSymbol.DIFFUSION.controlId.key,
                    size = 40.dp,
                    progressColor = OrpheusColors.echoLavender
                )
            }
        }
    }
}

@Preview(widthDp = 400, heightDp = 200)
@Composable
fun ReverbPanelPreview() {
    ReverbPanel(
        feature = ReverbViewModel.previewFeature()
    )
}
