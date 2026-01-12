package org.balch.orpheus.features.ai.chat
 
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.LiquidState
import kotlinx.coroutines.flow.MutableSharedFlow
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.config.AppConfig
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.ai.chat.widgets.ChatInputField
import org.balch.orpheus.features.ai.chat.widgets.ChatMessage
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageBubble
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import org.balch.orpheus.features.ai.generative.AiDashboard
import org.balch.orpheus.features.ai.generative.AiStatusMessage
import org.balch.orpheus.features.ai.isAiSupported
import org.balch.orpheus.features.ai.widgets.ApiKeyEntryScreen
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.dialogs.DraggableDialog
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * AI Chat dialog - a modeless, draggable dialog with liquid background.
 *
 * Features:
 * - Draggable title bar
 * - Liquid background effect
 * - Two modes:
 *    1. Chat Mode: Standard chat history + input
 *    2. Dashboard Mode: Only AiDashboard (when Drone/Solo is active)
 */
@Composable
fun ChatDialog(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = metroViewModel(),
    aiViewModel: AiOptionsViewModel = metroViewModel(),
    liquidState: LiquidState,
    position: Pair<Float, Float>,
    onPositionChange: (Float, Float) -> Unit,
    size: Pair<Float, Float>,
    onSizeChange: (Float, Float) -> Unit,
    onClose: () -> Unit = {},
) {
    val chatState by viewModel.stateFlow.collectAsState()
    val messages = chatState.messages
    val isLoading = chatState.isLoading

    val aiState by aiViewModel.stateFlow.collectAsState()
    val isDroneActive = aiState.isDroneActive
    val isSoloActive = aiState.isSoloActive
    val isDashboardMode = isDroneActive || isSoloActive

    DraggableDialog(
        title = if (isDashboardMode) "Dashboard" else AppConfig.CHAT_DISPLAY_NAME,
        showAvatar = !isDashboardMode,
        onClose = onClose,
        liquidState = liquidState,
        position = position,
        onPositionChange = onPositionChange,
        size = size,
        onSizeChange = onSizeChange,
        modifier = modifier
    ) {
        if (isDashboardMode) {
            // Dashboard Mode: Show only the AI Dashboard
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                AiDashboard(
                    inputLog = aiState.aiInputLog,
                    controlLog = aiState.aiControlLog,
                    statusMessages = aiState.aiStatusMessages,
                    isActive = true,
                    isSoloMode = isSoloActive,
                    sessionId = aiState.sessionId,
                    onSendInfluence = { aiViewModel.actions.onSendSoloInfluence(it) },
                    // Give it full space but respect layout
                    modifier = Modifier
                        .fillMaxWidth()
                        // Ensure it takes available space if needed, 
                        // effectively pushing content to top but allowing expansion
                        .weight(1f, fill = false) 
                )
            }
        } else {
            // Chat Mode: Standard chat UI
            val isApiKeySet = aiState.isApiKeySet
            
            ChatDialogContent(
                messages = messages,
                isLoading = isLoading,
                isApiKeySet = isApiKeySet,
                onSendMessage = viewModel.actions.onSendPrompt,
                onSaveApiKey = aiViewModel.actions.onSaveApiKey
            )
        }
    }
}

/**
 * Content layout for the standard chat view.
 */
@Composable
fun ChatDialogContent(
    messages: List<ChatMessage>,
    isLoading: Boolean,
    isApiKeySet: Boolean,
    onSendMessage: (String) -> Unit,
    onSaveApiKey: (AiProvider, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive or loading state changes
    LaunchedEffect(isLoading, messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!isAiSupported) {
            ChatNotAvailableScreen()
        } else if (!isApiKeySet) {
            // API Key entry screen
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                ApiKeyEntryScreen(
                    onSubmit = onSaveApiKey,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            // Chat messages
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageBubble(message = message)
                }
            }

            // Input field
            ChatInputField(
                isEnabled = !isLoading,
                onSendMessage = onSendMessage,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
@Preview
fun ChatDialogContentPreview() {
    val sampleMessages = listOf(
        ChatMessage(
            id = "1",
            text = "Hello! Can you help me create a sound?",
            type = ChatMessageType.User
        ),
        ChatMessage(
            id = "2",
            text = "Of course! I'd be happy to help you create a sound.",
            type = ChatMessageType.Agent
        )
    )

    OrpheusTheme {
        ChatDialogContent(
            messages = sampleMessages,
            isLoading = false,
            isApiKeySet = true,
            onSendMessage = {}
        )
    }
}

@Composable
@Preview
fun DashboardPreview() {
    // Fake data for preview
    val mockFlow = MutableSharedFlow<AiStatusMessage>(replay = 1)
    mockFlow.tryEmit(AiStatusMessage("Mock AI Message"))
    
    OrpheusTheme {
        Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
             AiDashboard(
                inputLog = mockFlow,
                controlLog = mockFlow,
                statusMessages = mockFlow,
                isActive = true,
                sessionId = 0,
                modifier = Modifier.fillMaxWidth()
             )
        }
    }
}
