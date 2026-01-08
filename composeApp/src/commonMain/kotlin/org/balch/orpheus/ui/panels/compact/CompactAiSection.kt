package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.ai.AiOptionsPanelActions
import org.balch.orpheus.features.ai.AiOptionsUiState
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.ai.chat.ChatDialogContent
import org.balch.orpheus.features.ai.chat.ChatPanelActions
import org.balch.orpheus.features.ai.chat.ChatUiState
import org.balch.orpheus.features.ai.chat.ChatViewModel
import org.balch.orpheus.features.ai.generative.AiDashboard
import org.balch.orpheus.features.ai.widgets.AiMode
import org.balch.orpheus.features.ai.widgets.AiModeSelector
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compact AI section for mobile portrait layout.
 *
 * Features:
 * - Mode selector at top (Drone/Solo/Tidal)
 * - Tidal mode triggers main REPL panel focus
 * - AI Dashboard shown when Drone/Solo active
 * - Chat interface when no AI mode active
 * - User input at bottom
 */
@Composable
fun CompactAiSection(
    modifier: Modifier = Modifier,
    aiViewModel: AiOptionsViewModel = metroViewModel(),
    chatViewModel: ChatViewModel = metroViewModel(),
    onShowRepl: () -> Unit = {}
) {
    val aiOptionsState by aiViewModel.stateFlow.collectAsState()
    val chatState by chatViewModel.stateFlow.collectAsState()

    CompactAiSectionLayout(
        modifier = modifier,
        aiOptionsState = aiOptionsState,
        aiOptionsActions = aiViewModel.actions,
        chatState = chatState,
        chatActions = chatViewModel.actions,
        onShowRepl = onShowRepl
    )
}

@Composable
fun CompactAiSectionLayout(
    modifier: Modifier = Modifier,
    aiOptionsState: AiOptionsUiState,
    aiOptionsActions: AiOptionsPanelActions,
    chatState: ChatUiState,
    chatActions: ChatPanelActions,
    onShowRepl: () -> Unit
) {
    // Determine current AI mode from states
    val currentMode: AiMode? = when {
        aiOptionsState.isDroneActive -> AiMode.DRONE
        aiOptionsState.isSoloActive -> AiMode.SOLO
        aiOptionsState.isReplActive -> AiMode.TIDAL
        else -> null
    }

    val isDashboardMode = currentMode == AiMode.DRONE || currentMode == AiMode.SOLO

    Column(modifier = modifier.fillMaxSize()) {
        // Mode selector at top
        AiModeSelector(
            selectedMode = currentMode,
            onModeSelected = { mode ->
                when (mode) {
                    AiMode.DRONE -> {
                        // Switch to Drone Mode
                        if (aiOptionsState.isReplActive) aiOptionsActions.onToggleRepl(false)
                        aiOptionsActions.onToggleDrone(false)
                        // Note: Drone/Solo are mutually handled by ViewModel, but explicit is fine
                    }

                    AiMode.SOLO -> {
                        // Switch to Solo Mode
                        if (aiOptionsState.isReplActive) aiOptionsActions.onToggleRepl(false)
                        aiOptionsActions.onToggleSolo(false)
                    }

                    AiMode.TIDAL -> {
                        // Switch to Tidal Mode
                        // Ensure other modes are off
                        if (aiOptionsState.isDroneActive) aiOptionsActions.onToggleDrone(false)
                        if (aiOptionsState.isSoloActive) aiOptionsActions.onToggleSolo(false)

                        // Activate Tidal Mode if not active
                        if (!aiOptionsState.isReplActive) {
                            aiOptionsActions.onToggleRepl(false) // Pass false to prevent chat dialog
                        }
                        
                        onShowRepl()
                    }

                    null -> {
                        // Deselect all
                        if (aiOptionsState.isDroneActive) aiOptionsActions.onToggleDrone(false)
                        if (aiOptionsState.isSoloActive) aiOptionsActions.onToggleSolo(false)
                        if (aiOptionsState.isReplActive) aiOptionsActions.onToggleRepl(false)
                    }
                }
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Main content area
        if (isDashboardMode) {
            // Dashboard Mode: Show AI Dashboard
            AiDashboard(
                inputLog = aiOptionsState.aiInputLog,
                controlLog = aiOptionsState.aiControlLog,
                statusMessages = aiOptionsState.aiStatusMessages,
                isActive = true,
                isSoloMode = aiOptionsState.isSoloActive,
                sessionId = aiOptionsState.sessionId,
                onSendInfluence = aiOptionsActions.onSendSoloInfluence,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp)
            )
        } else {
            // Chat Mode: Standard chat UI (Also shown during Tidal Mode)
            ChatDialogContent(
                messages = chatState.messages,
                isLoading = chatState.isLoading,
                isApiKeySet = aiOptionsState.isApiKeySet, // Using state from AiOptions
                onSendMessage = chatActions.onSendPrompt,
                onSaveApiKey = aiOptionsActions.onSaveApiKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp)
            )
        }
    }
}

@Preview
@Composable
fun CompactAiSectionPreview() {
    CompactAiSectionLayout(
        aiOptionsState = AiOptionsUiState(),
        aiOptionsActions = AiOptionsPanelActions.EMPTY,
        chatState = ChatUiState(),
        chatActions = ChatPanelActions.EMPTY,
        onShowRepl = {}
    )
}
