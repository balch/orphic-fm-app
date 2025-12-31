package org.balch.orpheus.features.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.widgets.ConfirmDialog
import org.balch.orpheus.ui.widgets.PresetNameDialog

/**
 * Preset management properties
 */
data class PresetProps(
    val presets: List<DronePreset>,
    val selectedPreset: DronePreset?,
    val presetActions: PresetPanelActions,
)

// Lighter orange for PATCHES panel
private val PatchesColor = Color(0xFFFFAB40)  // Light orange/amber

@Composable
fun PresetsPanel(
    modifier: Modifier = Modifier,
    presetsViewModel: PresetsViewModel = metroViewModel(),
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onDialogActiveChange: (Boolean) -> Unit = {}
) {
    val state by presetsViewModel.uiState.collectAsState()
    val actions = presetsViewModel.panelActions

    PresetsPanelLayout(
        modifier = modifier,
        uiState = state,
        actions = actions,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        onDialogActiveChange = onDialogActiveChange
    )
}

@Composable
fun PresetsPanelLayout(
    modifier: Modifier = Modifier,
    uiState: PresetUiState,
    actions: PresetPanelActions,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onDialogActiveChange: (Boolean) -> Unit = {}
) {
    var showNewDialog by remember { mutableStateOf(false) }
    var showOverrideDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val presetProps = PresetProps(
        presets = uiState.presets,
        selectedPreset = uiState.selectedPreset,
        presetActions = actions,
    )

    CollapsibleColumnPanel(
        title = "PATCH",
        color = PatchesColor,
        expandedTitle = "Presets",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        expandedWidth = 200.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Buttons row - ABOVE file list
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // NEW button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PatchesColor.copy(alpha = 0.2f))
                        .clickable {
                            showNewDialog = true
                            presetProps.presetActions.onDialogActiveChange(true)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "NEW",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PatchesColor
                    )
                }

                // OVR button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (presetProps.selectedPreset != null) Color(0xFFFF9500).copy(alpha = 0.2f)
                            else Color.Gray.copy(alpha = 0.1f)
                        )
                        .then(
                            if (presetProps.selectedPreset != null) Modifier.clickable {
                                showOverrideDialog = true
                                presetProps.presetActions.onDialogActiveChange(true)
                            }
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "OVR",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (presetProps.selectedPreset != null) Color(0xFFFF9500) else Color.Gray
                    )
                }

                // DEL button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (presetProps.selectedPreset != null) Color.Red.copy(alpha = 0.2f)
                            else Color.Gray.copy(alpha = 0.1f)
                        )
                        .then(
                            if (presetProps.selectedPreset != null) Modifier.clickable {
                                showDeleteDialog = true
                                presetProps.presetActions.onDialogActiveChange(true)
                            }
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "DEL",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (presetProps.selectedPreset != null) Color.Red.copy(alpha = 0.8f) else Color.Gray
                    )
                }
            }

            // Scrollable preset list with border
            val scrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, PatchesColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    presetProps.presets.forEach { preset ->
                        val isSelected = presetProps.selectedPreset?.name == preset.name
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isSelected) PatchesColor.copy(alpha = 0.2f)
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
                                color = if (isSelected) PatchesColor else Color.White.copy(
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
                presetProps.presetActions.onOverride()
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
                presetProps.presetActions.onDelete()
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
