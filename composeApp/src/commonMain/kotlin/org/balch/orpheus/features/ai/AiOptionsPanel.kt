package org.balch.orpheus.features.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.features.ai.widgets.AiButton
import org.balch.orpheus.features.ai.widgets.ApiKeyEntryCompact
import org.balch.orpheus.features.ai.widgets.UserKeyIndicator
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Unique colors for each AI button.
 */
object AiButtonColors {
    val drone = Color(0xFF4FC3F7)    // Light blue
    val solo = Color(0xFFFFB74D)     // Orange
    val repl = Color(0xFF81C784)     // Green
    val chat = Color(0xFFBA68C8)     // Purple
}

/**
 * AI Options panel with 4 feature buttons.
 * Note: ChatDialog is rendered separately at app level for proper z-ordering.
 */
@Composable
fun AiOptionsPanel(
    feature: SynthFeature<AiOptionsUiState, AiOptionsPanelActions> = AiOptionsViewModel.panelFeature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "AI",
        color = OrpheusColors.metallicBlue,
        expandedTitle = "Orpheus",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!uiState.isApiKeySet) {
                // Key entry UI
                ApiKeyEntryCompact(
                    onSubmit = actions.onSaveApiKey,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 2x2 Button Grid
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // User key indicator (if applicable)
                    if (uiState.isUserProvidedKey) {
                        UserKeyIndicator(
                            onRemove = actions.onClearApiKey,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    
                    // Top Row: Drone | Solo
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AiButton(
                            label = "Drone",
                            color = AiButtonColors.drone,
                            isActive = uiState.isDroneActive,
                            onClick = { actions.onToggleDrone(true) }
                        )
                        AiButton(
                            label = "Solo",
                            color = AiButtonColors.solo,
                            isActive = uiState.isSoloActive,
                            onClick = { actions.onToggleSolo(true) }
                        )
                    }
                    
                    // Bottom Row: REPL | Chat
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AiButton(
                            label = "Tidal",
                            color = AiButtonColors.repl,
                            isActive = uiState.isReplActive,
                            onClick = { actions.onToggleRepl(true) }
                        )
                        AiButton(
                            label = "AI",
                            color = AiButtonColors.chat,
                            isActive = uiState.showChatDialog,
                            onClick = { actions.onToggleChatDialog() }
                        )
                    }
                    
                    // Model Selector
                    ModelSelector(
                        selectedModel = uiState.selectedModel ?: AiModel.DEFAULT,
                        availableModels = uiState.availableModels,
                        onSelectModel = actions.onSelectModel,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
@Preview
fun AiOptionsLayoutPreview() {
    OrpheusTheme {
        AiOptionsPanel(
            feature = AiOptionsViewModel.previewFeature(
                AiOptionsUiState(
                    isApiKeySet = true,
                    isDroneActive = true,
                    isSoloActive = false
                )
            ),
            isExpanded = true
        )
    }
}

@Composable
@Preview
fun AiOptionsLayoutNoKeyPreview() {
    OrpheusTheme {
        AiOptionsPanel(
            feature = AiOptionsViewModel.previewFeature(
                AiOptionsUiState(isApiKeySet = false)
            ),
            isExpanded = true
        )
    }
}

/**
 * Compact model selector dropdown.
 */
@Composable
fun ModelSelector(
    selectedModel: AiModel,
    availableModels: List<AiModel>,
    onSelectModel: (AiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box(modifier = modifier) {
        // Current selection button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    OrpheusColors.midnightBlue.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.small
                )
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween // Text left, arrow right
        ) {
            Text(
                text = selectedModel.displayName,
                color = OrpheusColors.sterlingSilver,
                fontSize = 11.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
            
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = "Select",
                tint = OrpheusColors.sterlingSilver.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
        
        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = model.displayName,
                            fontSize = 12.sp
                        )
                    },
                    onClick = {
                        onSelectModel(model)
                        expanded = false
                    }
                )
            }
        }
    }
}
