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
import dev.zacsweers.metrox.viewmodel.metroViewModel
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
    modifier: Modifier = Modifier,
    viewModel: AiOptionsViewModel = metroViewModel(),
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val isDroneActive by viewModel.isDroneActive.collectAsState()
    val isSoloActive by viewModel.isSoloActive.collectAsState()
    val isReplActive by viewModel.isReplActive.collectAsState()
    val showChatDialog by viewModel.showChatDialog.collectAsState()
    val isApiKeySet by viewModel.apiKeyState.collectAsState()
    val isUserProvidedKey by viewModel.isUserProvidedKey.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()

    AiOptionsLayout(
        isApiKeySet = isApiKeySet,
        isUserProvidedKey = isUserProvidedKey,
        isDroneActive = isDroneActive,
        isSoloActive = isSoloActive,
        isReplActive = isReplActive,
        isChatActive = showChatDialog,
        selectedModel = selectedModel,
        availableModels = viewModel.availableModels,
        onToggleDrone = { viewModel.toggleDrone(true) },
        onToggleSolo = { viewModel.toggleSolo(true) },
        onToggleRepl = { viewModel.toggleRepl() },
        onToggleChat = viewModel::toggleChatDialog,
        onSelectModel = viewModel::selectModel,
        onSaveApiKey = viewModel::saveApiKey,
        onClearApiKey = viewModel::clearApiKey,
        modifier = modifier,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange
    )
}

/**
 * Layout for the AI panel content with 2x2 button grid.
 */
@Composable
fun AiOptionsLayout(
    isApiKeySet: Boolean,
    isUserProvidedKey: Boolean = false,
    isDroneActive: Boolean = false,
    isSoloActive: Boolean = false,
    isReplActive: Boolean = false,
    isChatActive: Boolean = false,
    selectedModel: AiModel = AiModel.DEFAULT,
    availableModels: List<AiModel> = AiModel.entries,
    onToggleDrone: () -> Unit = {},
    onToggleSolo: () -> Unit = {},
    onToggleRepl: () -> Unit = {},
    onToggleChat: () -> Unit = {},
    onSelectModel: (AiModel) -> Unit = {},
    onSaveApiKey: (String) -> Unit = {},
    onClearApiKey: () -> Unit = {},
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    CollapsibleColumnPanel(
        title = "AI",
        color = OrpheusColors.metallicBlue,
        expandedTitle = "Orpheus",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        expandedWidth = 180.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!isApiKeySet) {
                // Key entry UI
                ApiKeyEntryCompact(
                    onSubmit = onSaveApiKey,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 2x2 Button Grid
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // User key indicator (if applicable)
                    if (isUserProvidedKey) {
                        UserKeyIndicator(
                            onRemove = onClearApiKey,
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
                            isActive = isDroneActive,
                            onClick = onToggleDrone
                        )
                        AiButton(
                            label = "Solo",
                            color = AiButtonColors.solo,
                            isActive = isSoloActive,
                            onClick = onToggleSolo
                        )
                    }
                    
                    // Bottom Row: REPL | Chat
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AiButton(
                            label = "Tidal",
                            color = AiButtonColors.repl,
                            isActive = isReplActive,
                            onClick = onToggleRepl
                        )
                        AiButton(
                            label = "AI",
                            color = AiButtonColors.chat,
                            isActive = isChatActive,
                            onClick = onToggleChat
                        )
                    }
                    
                    // Model Selector
                    ModelSelector(
                        selectedModel = selectedModel,
                        availableModels = availableModels,
                        onSelectModel = onSelectModel,
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
        AiOptionsLayout(
            isApiKeySet = true,
            isDroneActive = true,
            isSoloActive = false,
            isExpanded = true
        )
    }
}

@Composable
@Preview
fun AiOptionsLayoutNoKeyPreview() {
    OrpheusTheme {
        AiOptionsLayout(
            isApiKeySet = false,
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
