package org.balch.orpheus.features.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.ai.widgets.AiButton
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

    AiOptionsLayout(
        isApiKeySet = viewModel.isApiKeySet,
        isDroneActive = isDroneActive,
        isSoloActive = isSoloActive,
        isReplActive = isReplActive,
        isChatActive = showChatDialog,
        onToggleDrone = viewModel::toggleDrone,
        onToggleSolo = viewModel::toggleSolo,
        onToggleRepl = viewModel::toggleRepl,
        onToggleChat = viewModel::toggleChatDialog,
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
    isDroneActive: Boolean = false,
    isSoloActive: Boolean = false,
    isReplActive: Boolean = false,
    isChatActive: Boolean = false,
    onToggleDrone: () -> Unit = {},
    onToggleSolo: () -> Unit = {},
    onToggleRepl: () -> Unit = {},
    onToggleChat: () -> Unit = {},
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    CollapsibleColumnPanel(
        title = "AI",
        color = OrpheusColors.warmGlow,
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
                // No API key message
                Text(
                    text = "Add GEMINI_API_KEY\nto local.properties",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    color = OrpheusColors.warmGlow.copy(alpha = 0.7f)
                )
            } else {
                // 2x2 Button Grid
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
                            label = "REPL",
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


