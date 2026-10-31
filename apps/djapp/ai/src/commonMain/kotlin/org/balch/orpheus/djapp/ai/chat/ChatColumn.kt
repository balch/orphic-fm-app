package org.balch.orpheus.djapp.ai.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.ai.ActionKind
import org.balch.orpheus.djapp.ai.AiSheetMode
import org.balch.orpheus.djapp.ai.DjAiFeature
import org.balch.orpheus.djapp.ai.DjAiFeedItem
import org.balch.orpheus.djapp.ai.DjAiPanelActions
import org.balch.orpheus.djapp.ai.DjAiPhase
import org.balch.orpheus.djapp.ai.DjAiUiState
import org.balch.orpheus.djapp.ai.DjAiViewModel
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.ideas.Idea
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

internal const val CHAT_INPUT_TAG = "chat-input"
internal const val CHAT_IDEAS_TAG = "chat-ideas"
internal const val CHAT_FEED_TAG = "chat-feed"

/** The chat's colours: warm for the user's own mixer moves, cool for the agent's tools. */
internal object ChatInk {
    val text = OrpheusColors.onSurfaceDark
    val dim = OrpheusColors.slateSilver
    val accent = OrpheusColors.metallicBlue
    val field = OrpheusColors.midnightBlue.copy(alpha = 0.4f)
    val bubble = OrpheusColors.midnightBlue.copy(alpha = 0.55f)
    val card = OrpheusColors.panelSurfaceDark
    val line = OrpheusColors.outlineDark
    val tool = OrpheusColors.metallicBlue
    val move = OrpheusColors.mixerFxAmber
    val agentMove = OrpheusColors.neonCyan
    val warn = OrpheusColors.warmGlow
}

/** Text at [nativeSp] as it reads on a native-density screen, whatever the canvas scale. */
@Composable
internal fun chatStyle(nativeSp: Float, color: Color = ChatInk.text, weight: FontWeight? = null): TextStyle =
    TextStyle(fontSize = legible(nativeSp), lineHeight = legible(nativeSp * 1.35f), color = color, fontWeight = weight)

/** [dp] grown with the legible text, so icons and tap targets keep their size beside it. */
@Composable
internal fun legibleDp(dp: Dp): Dp = dp * legible(1f).value

/**
 * The AI chat: header, ideas, the conversation, and the input pinned at the bottom. [showMixerToggle] adds the
 * header's Mixer switch; [onCollapse] adds a button that folds the column away.
 */
@Composable
fun ChatColumn(
    feature: DjAiFeature,
    modifier: Modifier = Modifier,
    showMixerToggle: Boolean,
    onCollapse: (() -> Unit)? = null,
) {
    val state by feature.stateFlow.collectAsState()
    // The sheet shares one with the Mixer; a column on its own keeps its own.
    val inputFocus = LocalChatInputFocus.current ?: remember { ChatInputFocus() }
    CompositionLocalProvider(LocalChatInputFocus provides inputFocus) {
        ChatColumnContent(state, feature.actions, modifier, showMixerToggle, onCollapse)
    }
}

@Composable
private fun ChatColumnContent(
    state: DjAiUiState,
    actions: DjAiPanelActions,
    modifier: Modifier,
    showMixerToggle: Boolean,
    onCollapse: (() -> Unit)?,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var headerBottom by remember { mutableIntStateOf(0) }
    val overlay = rememberOverlayFocus()
    val closeSettings = {
        settingsOpen = false
        overlay.onClose()
    }
    // Esc closes the settings; every other key goes on as before.
    Box(
        modifier
            .onPreviewKeyEvent { overlay.onPreviewKey(it, settingsOpen) { settingsOpen = false } }
            .focusRequester(overlay.host)
            .focusable(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatHeader(
                state = state,
                showMixerToggle = showMixerToggle,
                onSettings = {
                    if (settingsOpen) {
                        closeSettings()
                    } else {
                        overlay.onOpen()
                        settingsOpen = true
                    }
                },
                onMixer = { on -> actions.setSheetMode(if (on) AiSheetMode.MIXER else AiSheetMode.CHAT) },
                onNew = actions.newConversation,
                onCollapse = onCollapse,
                modifier = Modifier.onSizeChanged { headerBottom = it.height },
            )
            // Shown even with no ideas yet: they are made only once the user unfolds it.
            if (state.isKeySet) {
                IdeasStrip(
                    ideas = state.ideas,
                    open = state.ideasOpen,
                    onOpenChange = actions.setIdeasOpen,
                    onTry = actions.tryIdea,
                    modifier = Modifier.fillMaxWidth().testTag(CHAT_IDEAS_TAG),
                )
            }
            ChatFeed(
                feed = state.feed,
                queued = state.queued,
                onAction = actions.onAction,
                emptyHint = if (state.isKeySet) {
                    "Ask for a vibe, a change to the song, or help with the mix."
                } else {
                    "Add an API key in settings, behind the gear by the model's name, to start."
                },
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(CHAT_FEED_TAG),
            )
            if (state.phase != DjAiPhase.IDLE) WorkingLine()
            state.error?.let { ErrorBanner(it, actions.dismissError) }
            ChatInput(state, actions, Modifier.fillMaxWidth().testTag(CHAT_INPUT_TAG))
        }
        // An in-layout overlay rather than a Popup: a popup layer removed mid-measure crashes desktop Compose.
        if (settingsOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close settings",
                        onClick = closeSettings,
                    ),
            )
            val top = with(LocalDensity.current) { headerBottom.toDp() } + 12.dp
            // Focus moves into the popover, so Esc reaches the handler above without a click first.
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, top = top, bottom = 12.dp)
                    .focusRequester(focus)
                    .focusable(),
            ) {
                ChatSettings(
                    state = state,
                    actions = actions,
                    onClose = closeSettings,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                )
            }
        }
    }
}

/** Model name, settings, the Mixer switch, + New and the optional collapse. */
@Composable
private fun ChatHeader(
    state: DjAiUiState,
    showMixerToggle: Boolean,
    onSettings: () -> Unit,
    onMixer: (Boolean) -> Unit,
    onNew: () -> Unit,
    onCollapse: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            SettingsChip(state.selectedModel.displayName, onSettings)
            sessionCostLabel(state.sessionCostByModel, state.selectedModel.llmModel.id)?.let { label ->
                Text(
                    label,
                    style = chatStyle(LegibleText.SECONDARY, ChatInk.dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showMixerToggle) MixerSwitch(on = state.sheetMode == AiSheetMode.MIXER, onChange = onMixer)
        ChatButton("+ New", onNew)
        if (onCollapse != null) {
            HeaderIcon(onClick = onCollapse) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Collapse chat",
                    tint = ChatInk.dim,
                    modifier = Modifier.size(legibleDp(20.dp)),
                )
            }
        }
    }
}

/** The model's name behind a gear; it opens the settings. */
@Composable
private fun SettingsChip(model: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(legibleDp(5.dp)),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClickLabel = "Chat settings", role = Role.Button, onClick = onClick)
            .padding(horizontal = legibleDp(2.dp), vertical = legibleDp(4.dp)),
    ) {
        Icon(Icons.Filled.Settings, contentDescription = "Chat settings", tint = ChatInk.dim, modifier = Modifier.size(legibleDp(16.dp)))
        Text(
            model,
            style = chatStyle(LegibleText.SECONDARY, ChatInk.dim, FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeaderIcon(onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(legibleDp(3.dp)),
    ) { icon() }
}

/** "Mixer" with a lit dot while the Mixer shows beside the chat. */
@Composable
private fun MixerSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(shape)
            .background(if (on) ChatInk.accent.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (on) ChatInk.accent else ChatInk.line, shape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = legibleDp(8.dp), vertical = legibleDp(4.dp)),
    ) {
        Box(
            Modifier
                .size(legibleDp(7.dp))
                .clip(CircleShape)
                .background(if (on) ChatInk.accent else ChatInk.dim.copy(alpha = 0.5f)),
        )
        Text("Mixer", style = chatStyle(LegibleText.SECONDARY, if (on) ChatInk.text else ChatInk.dim, FontWeight.SemiBold), maxLines = 1)
    }
}

/** A pill button; [primary] fills it with the accent. */
@Composable
internal fun ChatButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    color: Color = ChatInk.accent,
) {
    val shape = RoundedCornerShape(50)
    val fill = when {
        !enabled -> Color.Transparent
        primary -> color.copy(alpha = 0.3f)
        else -> Color.Transparent
    }
    val ink = when {
        !enabled -> ChatInk.dim.copy(alpha = 0.45f)
        primary -> ChatInk.text
        else -> color
    }
    Text(
        label,
        style = chatStyle(LegibleText.SECONDARY, ink, FontWeight.SemiBold),
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, if (enabled) color.copy(alpha = 0.7f) else ChatInk.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = legibleDp(8.dp), vertical = legibleDp(4.dp)),
    )
}

/** Two or three idea cards above the conversation, each with Try; the label unfolds them on demand. */
@Composable
private fun IdeasStrip(
    ideas: List<Idea>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onTry: (Idea) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .background(ChatInk.card)
            .border(1.dp, ChatInk.line, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).toggleable(value = open, role = Role.Switch, onValueChange = onOpenChange),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (open) "Hide ideas" else "Show ideas",
                tint = ChatInk.dim,
                modifier = Modifier.size(legibleDp(16.dp)).rotate(if (open) 90f else 0f),
            )
            Text("Ideas", style = chatStyle(LegibleText.SECONDARY, ChatInk.dim, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (!open && ideas.isNotEmpty()) Text("${ideas.size}", style = chatStyle(LegibleText.SECONDARY, ChatInk.dim))
        }
        if (open && ideas.isEmpty()) {
            Text("Thinking up ideas…", style = chatStyle(LegibleText.SECONDARY, ChatInk.dim))
        }
        if (open) {
            for (idea in ideas.take(3)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        idea.text,
                        style = chatStyle(LegibleText.SECONDARY),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ChatButton("Try", { onTry(idea) }, primary = true)
                }
            }
        }
    }
}

/** A pulsing line over the input while the agent works. */
@Composable
private fun WorkingLine() {
    val transition = rememberInfiniteTransition(label = "chatWorking")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "chatWorkingPulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✦", style = chatStyle(LegibleText.SECONDARY, ChatInk.accent.copy(alpha = pulse)))
        Text("Working…", style = chatStyle(LegibleText.SECONDARY, ChatInk.accent))
    }
}

/** Dismissible error; a long one scrolls inside a bounded height instead of squeezing the feed. */
@Composable
private fun ErrorBanner(error: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ChatInk.warn.copy(alpha = 0.1f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            "⚠ $error",
            style = chatStyle(LegibleText.SECONDARY, ChatInk.warn),
            modifier = Modifier.weight(1f).heightIn(max = legibleDp(96.dp)).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "✕",
            style = chatStyle(LegibleText.SECONDARY, ChatInk.dim),
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 6.dp),
        )
    }
}

private val previewFeed = listOf(
    DjAiFeedItem.Request(0, "A dusty midnight groove with warm tape hiss"),
    DjAiFeedItem.Thinking(1, "Defining the key and tempo", "Leaning towards a minor-key groove around 122 BPM."),
    DjAiFeedItem.Tool(2, "Apply Vibe", running = false),
    DjAiFeedItem.Reply(3, "Here is **Midnight Tape**:\n- a slow minor groove at 122 BPM\n- tape hiss on the pad"),
    DjAiFeedItem.Move(4, EditOrigin.USER, "PAD level", "0.0 dB", "+2.0 dB"),
)

@Composable
@Preview(widthDp = 440, heightDp = 900)
private fun ChatColumnPreview() {
    OrpheusTheme {
        ChatColumn(
            feature = DjAiViewModel.previewFeature(
                DjAiUiState(
                    isKeySet = true,
                    feed = previewFeed,
                    nextId = 5,
                    ideas = listOf(Idea("Add a breakdown before the drop", "Add a breakdown before the drop", mix = false)),
                    ideasOpen = true,
                ),
            ),
            showMixerToggle = false,
        )
    }
}

@Composable
@Preview(widthDp = 440, heightDp = 900)
private fun ChatColumnActionPreview() {
    OrpheusTheme {
        ChatColumn(
            feature = DjAiViewModel.previewFeature(
                DjAiUiState(
                    isKeySet = true,
                    phase = DjAiPhase.GENERATING,
                    feed = previewFeed + DjAiFeedItem.Action(5, ActionKind.OPEN_MIXER, "Open the Mixer so I can make that change."),
                    nextId = 6,
                    queued = listOf("and a little darker"),
                ),
            ),
            showMixerToggle = true,
            onCollapse = {},
        )
    }
}
