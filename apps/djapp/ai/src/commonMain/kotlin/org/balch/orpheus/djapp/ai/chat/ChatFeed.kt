package org.balch.orpheus.djapp.ai.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.ai.ActionKind
import org.balch.orpheus.djapp.ai.DjAiFeedItem
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.copyPlainText

/** How close (px) the previous tail's bottom edge must be to the viewport bottom to count as "at the bottom". */
private const val FOLLOW_TAIL_SLACK_PX = 24

/** How long (ns) an expanding thinking row's growing bottom edge is tracked; covers the expand animation. */
private const val EXPAND_FOLLOW_WINDOW_NANOS = 500_000_000L

/** About two secondary lines at 1x, grown with the canvas so a clipped row fades out whole. */
private val TOP_FADE = 40.dp

/** The accept and decline labels of an [ActionKind] card. */
internal fun ActionKind.buttons(): Pair<String, String> = when (this) {
    ActionKind.OPEN_MIXER -> "Open Mixer" to "Not now"
    ActionKind.CONFIRM_WIPE -> "Clear All" to "Keep my mix"
}

/**
 * The conversation, oldest first, then the messages queued behind the running turn. A new row is followed only
 * while the viewport sits at the bottom, so reading further up is never yanked away.
 */
@Composable
internal fun ChatFeed(
    feed: List<DjAiFeedItem>,
    queued: List<String>,
    onAction: (id: Long, accept: Boolean) -> Unit,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var expandedIds by remember { mutableStateOf(setOf<Long>()) }
    var prevSize by remember { mutableStateOf(0) }
    val total = feed.size + queued.size

    LaunchedEffect(total) {
        val oldSize = prevSize
        prevSize = total
        // A new conversation starts from an empty feed, so its recycled ids must not start expanded.
        if (feed.isEmpty()) expandedIds = emptySet()
        if (total == 0) return@LaunchedEffect
        if (oldSize == 0 || total < oldSize) {
            listState.scrollToItem(total - 1)
            return@LaunchedEffect
        }
        // Decided in pixels: chase the new row only if the previous tail's bottom edge sat at the viewport bottom.
        // A tall expanded row keeps its index visible while the user reads its middle, so an index check cannot work.
        val info = listState.layoutInfo
        val oldLast = info.visibleItemsInfo.find { it.index == oldSize - 1 }
        val wasAtBottom = oldLast != null && oldLast.offset + oldLast.size <= info.viewportEndOffset + FOLLOW_TAIL_SLACK_PX
        if (wasAtBottom) listState.scrollToItem(total - 1)
    }

    if (total == 0) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                emptyHint,
                style = chatStyle(LegibleText.SECONDARY, ChatInk.dim),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        return
    }

    val fadeDp = legibleDp(TOP_FADE)
    LazyColumn(
        state = listState,
        // Rows scrolled off the top fade out rather than ending in a hard cut under the ideas.
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (listState.canScrollBackward) {
                    val fade = (fadeDp.toPx() / size.height).coerceAtMost(1f)
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black), blendMode = BlendMode.DstIn)
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items = feed, key = { _, item -> item.id }) { index, item ->
            when (item) {
                is DjAiFeedItem.Request -> RequestBubble(item.text)
                is DjAiFeedItem.Thinking -> ThinkingRow(
                    item = item,
                    expanded = item.id in expandedIds,
                    onToggle = {
                        val expanding = item.id !in expandedIds
                        expandedIds = if (expanding) expandedIds + item.id else expandedIds - item.id
                        // The body unfolds below the label over the animation, so track it frame by frame: scroll by
                        // however much the row overflows the bottom, never past the label. A user scroll cancels this.
                        if (expanding) {
                            scope.launch {
                                val start = withFrameNanos { it }
                                var elapsed = 0L
                                while (elapsed <= EXPAND_FOLLOW_WINDOW_NANOS) {
                                    val info = listState.layoutInfo
                                    val row = info.visibleItemsInfo.find { it.index == index } ?: break
                                    val step = minOf(row.offset + row.size - info.viewportEndOffset, row.offset - info.viewportStartOffset)
                                    if (step > 0) listState.scrollBy(step.toFloat())
                                    elapsed = withFrameNanos { it } - start
                                }
                            }
                        }
                    },
                )
                is DjAiFeedItem.Tool -> OneLine(
                    (if (item.running) "⋯ " else "✓ ") + (item.summary ?: item.name),
                    ChatInk.tool,
                    singleLine = true,
                )
                is DjAiFeedItem.Reply -> ReplyRow(item.text)
                is DjAiFeedItem.Move -> OneLine(
                    "${item.origin.who()}: ${item.label} ${item.before} → ${item.after}",
                    if (item.origin == EditOrigin.USER) ChatInk.move else ChatInk.agentMove,
                )
                is DjAiFeedItem.Notice -> OneLine(item.text, ChatInk.dim, italic = true)
                is DjAiFeedItem.Action -> ActionCard(item, onAction)
                is DjAiFeedItem.Divider -> DividerRow(item.text)
            }
        }
        itemsIndexed(items = queued, key = { i, _ -> "queued-$i" }) { _, text -> RequestBubble(text, queued = true) }
    }
}

private fun EditOrigin.who() = when (this) {
    EditOrigin.USER -> "you"
    EditOrigin.AGENT -> "agent"
    EditOrigin.MCP -> "MCP"
}

/** Tool steps, mixer moves and notices: one line of secondary text; tool steps ellipsize, the rest wrap when they must. */
@Composable
private fun OneLine(text: String, color: Color, italic: Boolean = false, singleLine: Boolean = false) {
    val style = chatStyle(LegibleText.SECONDARY, color)
    Text(
        text,
        style = if (italic) style.copy(fontStyle = FontStyle.Italic) else style,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

/** The user's message as a bubble on the right, with a copy glyph; a [queued] one waits dimmed for the turn to end. */
@Composable
private fun RequestBubble(text: String, queued: Boolean = false) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.End) {
        Row(
            Modifier.fillMaxWidth(0.9f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!queued) {
                Text(
                    if (copied) "✓" else "⧉",
                    style = chatStyle(LegibleText.SECONDARY, if (copied) ChatInk.accent else ChatInk.dim),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            scope.launch { clipboard.copyPlainText(text) }
                            copied = true
                        }
                        .padding(horizontal = 4.dp),
                )
            }
            Text(
                text,
                style = chatStyle(LegibleText.CHAT_BODY),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .alpha(if (queued) 0.55f else 1f)
                    .clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                    .background(ChatInk.bubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (queued) {
            Text("queued", style = chatStyle(LegibleText.SECONDARY, ChatInk.dim, FontWeight.SemiBold), modifier = Modifier.padding(top = 2.dp, end = 4.dp))
        }
    }
}

/** The agent's reply: rendered Markdown at chat size, under a small marker. */
@Composable
private fun ReplyRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Top) {
        Text("✦", style = chatStyle(LegibleText.CHAT_BODY, ChatInk.accent), modifier = Modifier.padding(end = 8.dp))
        ChatMarkdown(text, chatStyle(LegibleText.CHAT_BODY), Modifier.weight(1f))
    }
}

/** A reasoning segment behind a chevron; its raw text unfolds below. A row with no text yet has nothing to open. */
@Composable
private fun ThinkingRow(item: DjAiFeedItem.Thinking, expanded: Boolean, onToggle: () -> Unit) {
    val expandable = item.text.isNotBlank()
    val rotation by animateFloatAsState(if (expanded && expandable) 90f else 0f, label = "thinkingChevron")
    val style = chatStyle(LegibleText.SECONDARY, ChatInk.dim)
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .then(if (expandable) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
                .padding(vertical = 2.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = when {
                    !expandable -> null
                    expanded -> "Collapse thinking"
                    else -> "Expand thinking"
                },
                tint = ChatInk.dim.copy(alpha = if (expandable) 0.8f else 0.35f),
                modifier = Modifier.size(legibleDp(16.dp)).rotate(rotation),
            )
            Text(item.headline ?: "Thinking…", style = style, modifier = Modifier.padding(start = 4.dp))
        }
        AnimatedVisibility(visible = expanded && expandable, enter = expandVertically(), exit = shrinkVertically()) {
            Text(
                item.text.trim(),
                style = style.copy(fontStyle = FontStyle.Italic, color = ChatInk.dim.copy(alpha = 0.75f)),
                modifier = Modifier.padding(start = legibleDp(20.dp), top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

/** The agent's question with its two answers; once answered the buttons switch off and the choice shows. */
@Composable
private fun ActionCard(item: DjAiFeedItem.Action, onAction: (id: Long, accept: Boolean) -> Unit) {
    val (accept, decline) = item.kind.buttons()
    val shape = RoundedCornerShape(10.dp)
    val live = !item.resolved
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ChatInk.card)
            .border(1.dp, if (live) ChatInk.accent.copy(alpha = 0.6f) else ChatInk.line, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(item.text, style = chatStyle(LegibleText.CHAT_BODY, if (live) ChatInk.text else ChatInk.dim))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val warn = if (item.kind == ActionKind.CONFIRM_WIPE) ChatInk.warn else ChatInk.accent
            ChatButton(accept, { onAction(item.id, true) }, enabled = live, primary = true, color = warn)
            ChatButton(decline, { onAction(item.id, false) }, enabled = live)
        }
        item.answer?.let { yes ->
            Text("You chose: ${if (yes) accept else decline}", style = chatStyle(LegibleText.SECONDARY, ChatInk.dim))
        }
    }
}

/** A thin rule with a label, where a new conversation starts. */
@Composable
private fun DividerRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(ChatInk.line))
        Text(text, style = chatStyle(LegibleText.SECONDARY, ChatInk.dim), modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(ChatInk.line))
    }
}
