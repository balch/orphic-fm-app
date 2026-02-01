package org.balch.orpheus.features.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.features.visualizations.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.dialogs.ConfirmDialog
import org.balch.orpheus.ui.widgets.dialogs.PresetNameDialog

/**
 * Preset management properties
 */
data class PresetProps(
    val presets: List<DronePreset>,
    val selectedPreset: DronePreset?,
    val presetActions: PresetPanelActions,
)

/**
 * PresetsPanel consuming feature() interface.
 */
@Composable
fun PresetsPanel(
    feature: PresetsFeature = PresetsViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    var showNewDialog by remember { mutableStateOf(false) }
    var showOverrideDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Extract values from the sealed interface
    val currentState = uiState
    val (presets, selectedPreset) = when (currentState) {
        is PresetUiState.Loading -> emptyList<DronePreset>() to null
        is PresetUiState.Loaded -> currentState.presets to currentState.selectedPreset
    }

    val presetProps = PresetProps(
        presets = presets,
        selectedPreset = selectedPreset,
        presetActions = actions,
    )

    val buttonColors = ButtonColors(
        containerColor = OrpheusColors.patchOrange.copy(alpha = 0.2f),
        contentColor = OrpheusColors.presetOrange,
        disabledContainerColor = Color.Gray.copy(alpha = 0.5f),
        disabledContentColor = Color.White.copy(alpha = 0.7f)
    )

    CollapsibleColumnPanel(
        title = "PATCH",
        color = OrpheusColors.presetOrange,
        expandedTitle = "Memento",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        // Buttons row - ABOVE file list
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // NEW button
            Button(
                onClick = {
                    showNewDialog = true
                    presetProps.presetActions.onDialogActiveChange(true)
                },
                colors = buttonColors
            ) {
                Text(
                    "NEW",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Button(
                onClick = {
                    showOverrideDialog = true
                    presetProps.presetActions.onDialogActiveChange(true)
                },
                colors = buttonColors,
                enabled = (presetProps.selectedPreset != null),
            ) {
                Text(
                    "OVR",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // DEL button
            Button(
                onClick = {
                    showDeleteDialog = true
                    presetProps.presetActions.onDialogActiveChange(true)
                },
                enabled = (presetProps.selectedPreset != null),
                colors = buttonColors,
            ) {

                Text(
                    "DEL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Scrollable preset list with border
        val scrollState = rememberScrollState()

        Row(
            modifier = Modifier
                .widthIn(min = 240.dp)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    OrpheusColors.presetOrange.copy(alpha = 0.3f),
                    RoundedCornerShape(6.dp)
                )
                .background(OrpheusColors.darkVoid.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                presetProps.presets.forEach { preset ->
                    val isSelected = presetProps.selectedPreset?.name == preset.name
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isSelected) OrpheusColors.presetOrange.copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                            .clickable {
                                presetProps.presetActions.onSelect(preset)
                                presetProps.presetActions.onApply(preset)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            preset.name,
                            fontSize = 10.sp,
                            color = if (isSelected) OrpheusColors.presetOrange else Color.White.copy(
                                alpha = 0.7f
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (showNewDialog) {
        PresetNameDialog(
            onConfirm = { name ->
                presetProps.presetActions.onNew(name)
                showNewDialog = false
                presetProps.presetActions.onDialogActiveChange(false)
            },
            onDismiss = {
                showNewDialog = false
                presetProps.presetActions.onDialogActiveChange(false)
            }
        )
    }

    if (showOverrideDialog) {
        ConfirmDialog(
            title = "Overwrite Preset",
            message = "Overwrite '${presetProps.selectedPreset?.name ?: ""}'?",
            onConfirm = {
                presetProps.selectedPreset?.let { presetProps.presetActions.onOverride(it) }
                showOverrideDialog = false
                presetProps.presetActions.onDialogActiveChange(false)
            },
            onDismiss = {
                showOverrideDialog = false
                presetProps.presetActions.onDialogActiveChange(false)
            },
            isDestructive = true
        )
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete Preset",
            message = "Delete '${presetProps.selectedPreset?.name ?: ""}'?",
            onConfirm = {
                presetProps.selectedPreset?.let { presetProps.presetActions.onDelete(it) }
                showDeleteDialog = false
                presetProps.presetActions.onDialogActiveChange(false)
            },
            onDismiss = {
                showDeleteDialog = false
                presetProps.presetActions.onDialogActiveChange(false)
            },
            isDestructive = true
        )
    }
}

// Preview support
@Preview(widthDp = 400, heightDp = 400)
@Composable
fun PresetsPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        PresetsPanel(
            isExpanded = true,
            feature = PresetsViewModel.previewFeature(),
        )
    }
}
