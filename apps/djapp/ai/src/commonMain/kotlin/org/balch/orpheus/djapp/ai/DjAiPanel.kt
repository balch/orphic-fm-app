package org.balch.orpheus.djapp.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
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

/**
 * DJ AI vibe-creation panel.
 *
 * Layout, top -> bottom:
 * - Config strip: model dropdown + API key control.
 * - Prompt input: single line; Enter / IME Send submits and dismisses the keyboard.
 * - Dismissible error strip (scrolls internally when the message is long).
 * - Unified agent feed: chronological tool rows, chevron-expandable thinking rows
 *   (collapsed by default), and the agent's reply. Auto-scrolls on new rows only.
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

        // The prompt row is the input box when idle, and a one-line status strip while the
        // agent runs (and after a result). The agent's reply lives in the feed below.
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
                onCancel = actions.reset,
                onNew = actions.reset,
            )
        }

        uiState.error?.let { error ->
            ErrorBanner(error = error, onDismiss = actions.dismissError)
        }

        AiFeed(
            feed = uiState.feed,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * Dismissible error strip. Long errors (e.g. a vibe JSON parse failure dumping the whole
 * exception) scroll within a bounded height instead of squeezing the feed below.
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
                // Dismiss the keyboard so the feed is visible while generating.
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
 * Replaces the prompt input while the agent is running or after a result. A one-line
 * status strip: pulsing indicator + status text, with ✕ Cancel while working and a
 * "＋ New" affordance once the run is done. The agent's reply renders in the feed.
 */
@Composable
private fun WorkingStatusCard(
    working: Boolean,
    onCancel: () -> Unit,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "✦",
            fontSize = 14.sp,
            color = OrpheusColors.metallicBlue.copy(alpha = if (working) pulse else 1f),
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = if (working) "Composing your vibe…" else "Vibe ready",
            style = MaterialTheme.typography.labelSmall,
            color = OrpheusColors.metallicBlue.copy(alpha = 0.9f),
            modifier = Modifier.weight(1f),
        )
        if (working) {
            Text(
                text = "✕ Cancel",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.warmGlow,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        } else {
            Text(
                text = "＋ New",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.sterlingSilver,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onNew)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Unified chronological agent feed. Thinking rows expand in place behind a chevron;
 * tool rows and the reply are plain rows. Auto-scrolls when a NEW row appears, never
 * on text accumulation inside an existing row, so reading an expanded row while the
 * agent streams does not fight the scroll.
 */
@Composable
private fun AiFeed(
    feed: List<DjAiFeedItem>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var expandedIds by remember { mutableStateOf(setOf<Long>()) }

    // New run / reset: drop stale expansion state so a fresh run's recycled ids
    // don't start expanded.
    LaunchedEffect(feed.isEmpty()) {
        if (feed.isEmpty()) expandedIds = emptySet()
    }

    LaunchedEffect(feed.size) {
        if (feed.isNotEmpty()) {
            listState.scrollToItem(feed.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = feed, key = { it.id }) { item ->
            when (item) {
                is DjAiFeedItem.Thinking -> ThinkingRow(
                    item = item,
                    expanded = item.id in expandedIds,
                    onToggle = {
                        expandedIds = if (item.id in expandedIds) {
                            expandedIds - item.id
                        } else {
                            expandedIds + item.id
                        }
                    },
                )
                is DjAiFeedItem.Tool -> Text(
                    text = if (item.running) "🔧 ${item.name}…" else "✓ ${item.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OrpheusColors.sterlingSilver,
                )
                is DjAiFeedItem.Reply -> ReplyRow(text = item.text)
            }
        }
    }
}

/**
 * One thinking segment: a tappable label row with a rotating chevron, and the raw
 * reasoning text revealed below it. Rows with no body text yet (headline arrived,
 * body still streaming, or empty segment) render without an active chevron.
 */
@Composable
private fun ThinkingRow(
    item: DjAiFeedItem.Thinking,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandable = item.text.isNotBlank()
    val rotation by animateFloatAsState(
        targetValue = if (expanded && expandable) 90f else 0f,
        label = "thinkingChevron",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = when {
                    !expandable -> null
                    expanded -> "Collapse thinking"
                    else -> "Expand thinking"
                },
                tint = OrpheusColors.sterlingSilver.copy(alpha = if (expandable) 0.7f else 0.3f),
                modifier = Modifier.size(14.dp).rotate(rotation),
            )
            Text(
                text = item.headline ?: "Thinking…",
                style = MaterialTheme.typography.labelSmall,
                color = OrpheusColors.sterlingSilver.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded && expandable,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Text(
                text = item.text.trim(),
                style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                color = OrpheusColors.sterlingSilver.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
        }
    }
}

/** The agent's reply row: ✦ marker + the reply text in the primary text style. */
@Composable
private fun ReplyRow(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(
            text = "✦",
            fontSize = 13.sp,
            color = OrpheusColors.metallicBlue,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = text,
            style = TextStyle(fontSize = 13.sp, color = OrpheusColors.pureWhite),
        )
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
                    phase = DjAiPhase.GENERATING,
                    feed = listOf(
                        DjAiFeedItem.Tool(id = 0, name = "Vibe Schema", running = false),
                        DjAiFeedItem.Thinking(
                            id = 1,
                            headline = "Defining the Key and Tempo",
                            text = "Leaning towards a minor-key groove around 122 BPM…",
                        ),
                        DjAiFeedItem.Tool(id = 2, name = "Apply Vibe", running = true),
                        DjAiFeedItem.Reply(id = 3, text = "Spinning up a dusty midnight groove."),
                    ),
                    nextId = 4,
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
