package org.balch.orpheus.djapp.ai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.features.ai.widgets.ApiKeyEntryCompact
import org.balch.orpheus.features.ai.widgets.ModelSelector
import org.balch.orpheus.features.ai.widgets.UserKeyIndicator
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.max

/**
 * DJ AI vibe-creation panel.
 *
 * Layout, top -> bottom:
 * - Config strip: model dropdown + API key control.
 * - Prompt input: single line; Enter / IME Send submits and dismisses the keyboard.
 * - Dismissible error strip (scrolls internally when the message is long).
 * - Two resizable panes separated by a draggable divider: auto-scrolling Activity feed
 *   on top, user-scrollable Thinking feed (italic, dimmed) on the bottom.
 */
@Composable
fun DjAiPanel(
    feature: DjAiFeature = DjAiViewModel.feature(),
    modifier: Modifier = Modifier,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    Column(
        modifier = modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConfigStrip(
            selectedModel = uiState.selectedModel,
            availableModels = uiState.availableModels,
            isKeySet = uiState.isKeySet,
            onSelectModel = actions.selectModel,
            onSaveKey = actions.saveKey,
            onClearKey = actions.clearKey,
        )

        // The prompt row is the input box when idle, and a working/output status card while the
        // agent runs (and after a result) — so the agent's main reply is always front-and-center
        // instead of scrolling away in the activity log.
        if (uiState.phase == DjAiPhase.IDLE) {
            PromptInput(
                draft = uiState.promptDraft,
                isKeySet = uiState.isKeySet,
                onDraftChange = actions.updateDraft,
                onSubmit = actions.submit,
            )
        } else {
            WorkingStatusCard(
                working = uiState.phase == DjAiPhase.GENERATING,
                reply = uiState.assistantReply,
                onNew = actions.reset,
            )
        }

        uiState.error?.let { error ->
            ErrorBanner(error = error, onDismiss = actions.dismissError)
        }

        ActivityThinkingPanes(
            activity = uiState.activity,
            thinking = uiState.thinking,
            isGenerating = uiState.phase == DjAiPhase.GENERATING,
            onCancel = actions.reset,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * Dismissible error strip. Long errors (e.g. a vibe JSON parse failure dumping the whole
 * exception) scroll within a bounded height instead of squeezing the feeds below.
 */
@Composable
private fun ErrorBanner(
    error: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(OrpheusColors.warmGlow.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "⚠ $error",
            style = MaterialTheme.typography.labelMedium,
            color = OrpheusColors.warmGlow,
            modifier = Modifier
                .weight(1f)
                .heightIn(max = 96.dp)
                .verticalScroll(rememberScrollState()),
        )
        Text(
            "✕",
            style = MaterialTheme.typography.labelMedium,
            color = OrpheusColors.sterlingSilver,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ConfigStrip(
    selectedModel: AiModel,
    availableModels: List<AiModel>,
    isKeySet: Boolean,
    onSelectModel: (AiModel) -> Unit,
    onSaveKey: (AiProvider, String) -> Unit,
    onClearKey: (AiProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelSelector(
            selectedModel = selectedModel,
            availableModels = availableModels,
            onSelectModel = onSelectModel,
            showDropdownArrow = false,
            horizontalPadding = 10.dp,
        )

        if (isKeySet) {
            UserKeyIndicator(
                aiProvider = selectedModel.aiProvider,
                onRemove = onClearKey,
            )
        } else {
            ApiKeyEntryCompact(onSubmit = onSaveKey)
        }
    }
}

@Composable
private fun PromptInput(
    draft: String,
    isKeySet: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // IMEs deliver their Send/Enter key through FOUR different mechanisms, and Samsung
        // Keyboard picks per version/mode, so every path below must funnel into onSubmit():
        //   1. performEditorAction(SEND)      -> KeyboardActions(onSend)      [Gboard]
        //   2. performEditorAction(DONE/GO)   -> KeyboardActions(onDone/onGo) [Samsung, some versions]
        //   3. commitText("\n")               -> newline intercept in onValueChange [Samsung, classic]
        //   4. sendKeyEvent(KEYCODE_ENTER)    -> onPreviewKeyEvent            [hardware keys]
        // Note singleLine=true does NOT filter committed newlines from onValueChange in the
        // string-based BasicTextField — it only sets layout constraints and IME hints.
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        val submitIfAble = {
            if (isKeySet) {
                onSubmit()
                // Dismiss the keyboard so the Activity/Thinking feeds are visible while generating.
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        }
        BasicTextField(
            value = draft,
            onValueChange = { new ->
                if (new.any { it == '\n' }) {
                    onDraftChange(new.filterNot { it == '\n' })
                    submitIfAble()
                } else {
                    onDraftChange(new)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { submitIfAble() },
                onDone = { submitIfAble() },
                onGo = { submitIfAble() },
            ),
            textStyle = TextStyle(fontSize = 13.sp, color = OrpheusColors.pureWhite),
            cursorBrush = SolidColor(OrpheusColors.metallicBlue),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(OrpheusColors.midnightBlue.copy(alpha = 0.4f))
                .padding(10.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                        !event.isShiftPressed
                    ) {
                        submitIfAble()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            "Describe a vibe…",
                            style = TextStyle(fontSize = 13.sp, color = OrpheusColors.sterlingSilver.copy(alpha = 0.5f)),
                        )
                    }
                    inner()
                }
            },
        )
        if (!isKeySet) {
            Text(
                "Add an API key to create",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.sterlingSilver.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Replaces the prompt input while the agent is running or after a result. Shows a pulsing
 * indicator + status line, the agent's latest reply (so it is never lost in the activity log),
 * and — once the run is done — a "＋ New" affordance that returns to the input for another prompt.
 */
@Composable
private fun WorkingStatusCard(
    working: Boolean,
    reply: String,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "aiWorking")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "aiWorkingPulse",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(OrpheusColors.midnightBlue.copy(alpha = 0.4f))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "✦",
            fontSize = 14.sp,
            color = OrpheusColors.metallicBlue.copy(alpha = if (working) pulse else 1f),
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (working) "Composing your vibe…" else "Vibe ready",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.metallicBlue.copy(alpha = 0.9f),
            )
            if (reply.isNotBlank()) {
                Text(
                    text = reply,
                    style = TextStyle(fontSize = 13.sp, color = OrpheusColors.pureWhite),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 96.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
        if (!working) {
            Text(
                text = "＋ New",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.sterlingSilver,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onNew)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ActivityThinkingPanes(
    activity: List<String>,
    thinking: List<String>,
    isGenerating: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activityWeight by remember { mutableStateOf(0.5f) }

    Column(modifier = modifier) {
        ActivityFeed(
            activity = activity,
            isGenerating = isGenerating,
            onCancel = onCancel,
            modifier = Modifier.weight(activityWeight).fillMaxWidth(),
        )

        DraggableHandle(
            onDrag = { deltaPx ->
                activityWeight = (activityWeight + deltaPx / DIVIDER_DRAG_RANGE_PX).coerceIn(0.2f, 0.8f)
            },
        )

        ThinkingFeed(
            thinking = thinking,
            modifier = Modifier.weight(1f - activityWeight).fillMaxWidth(),
        )
    }
}

/**
 * Divider handle between the Activity and Thinking panes. Reports raw vertical drag delta (px);
 * the caller converts that into a weight delta via [DIVIDER_DRAG_RANGE_PX].
 */
@Composable
private fun DraggableHandle(onDrag: (Float) -> Unit, modifier: Modifier = Modifier) {
    val dragState = rememberDraggableState(onDelta = onDrag)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .draggable(orientation = Orientation.Vertical, state = dragState)
            .background(OrpheusColors.sterlingSilver.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(3.dp)
                .fillMaxWidth(0.15f)
                .clip(RoundedCornerShape(2.dp))
                .background(OrpheusColors.sterlingSilver.copy(alpha = 0.4f)),
        )
    }
}

/** Approximate pane-region height (px) used to convert a divider drag delta into a weight delta. */
private const val DIVIDER_DRAG_RANGE_PX = 600f

@Composable
private fun ActivityFeed(
    activity: List<String>,
    isGenerating: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activity.size) {
        if (activity.isNotEmpty()) {
            listState.scrollToItem(max(0, activity.size - 1))
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ACTIVITY",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.metallicBlue.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 2.dp),
            )
            if (isGenerating) {
                Text(
                    "✕ Cancel",
                    style = MaterialTheme.typography.labelSmall,
                    color = OrpheusColors.warmGlow,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(activity) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = OrpheusColors.sterlingSilver,
                )
            }
        }
    }
}

@Composable
private fun ThinkingFeed(thinking: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    Column(modifier = modifier) {
        Text(
            "THINKING",
            style = MaterialTheme.typography.labelSmall,
            color = OrpheusColors.metallicBlue.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(thinking) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                    color = OrpheusColors.sterlingSilver.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
@Preview(widthDp = 400, heightDp = 700)
private fun DjAiPanelPreview() {
    OrpheusTheme {
        DjAiPanel(
            feature = DjAiViewModel.previewFeature(
                DjAiUiState(
                    isKeySet = true,
                    activity = listOf(
                        "🔧 Vibe Schema…",
                        "✓ Vibe Schema",
                        "🔧 Apply Vibe…",
                    ),
                    thinking = listOf(
                        "Considering tempo and swing…",
                        "Leaning towards a minor-key groove…",
                    ),
                )
            ),
        )
    }
}

@Composable
@Preview(widthDp = 400, heightDp = 700)
private fun DjAiPanelNoKeyPreview() {
    OrpheusTheme {
        DjAiPanel(
            feature = DjAiViewModel.previewFeature(DjAiUiState(isKeySet = false)),
        )
    }
}
