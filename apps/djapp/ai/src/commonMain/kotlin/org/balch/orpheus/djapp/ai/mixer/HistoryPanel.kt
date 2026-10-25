package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.coroutines.delay
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.ai.collab.MixHistory
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.features.pulsar.bench.CaptureRow

internal fun historyNodeTag(id: Int) = "history-node-$id"
internal fun historyHearTag(id: Int) = "history-hear-$id"
internal fun historyWritesTag(id: Int) = "history-writes-$id"
internal fun historyRollBackTag(id: Int) = "history-rollback-$id"
internal fun historyConfirmTag(id: Int) = "history-confirm-$id"
internal fun historyKeepTag(id: Int) = "history-keep-$id"

private const val AGE_TICK_MS = 15_000L
private val NODE_TARGET = 44.dp

/** The wall clock, refreshed every 15 s so ages stay current while shown. */
@Composable
internal fun rememberNowMs(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(AGE_TICK_MS)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

/** "now" under a minute, then minutes, then hours. */
internal fun ageText(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h"
    }
}

@Composable
internal fun HistoryTitle(state: AiMixerUiState, actions: AiMixerActions, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "History · ${state.vibeName}",
            style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.text, FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        MixerButton("◆ Mark point", actions.markPoint)
        if (onClose != null) {
            Text(
                "Close",
                style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.text, FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.clickable(role = Role.Button, onClick = onClose).padding(4.dp),
            )
        }
    }
}

/** The mix changes newest first, Now at the top and File at the bottom; only a change's node turns it off or on. */
@Composable
internal fun HistoryTimeline(state: AiMixerUiState, actions: AiMixerActions, nowMs: Long, modifier: Modifier = Modifier) {
    var rolling by remember { mutableStateOf<Int?>(null) }
    val rows = state.history
    val changeCount = rows.count { it is ChangeRow }
    Column(modifier) {
        NowLine(nowDetail(rows, state.onCount))
        // Rows stay oldest-first in state, so a point's "after" is still the rows that follow it there.
        for (i in rows.indices.reversed()) {
            when (val row = rows[i]) {
                is ChangeRow -> ChangeLine(row, nowMs, actions)
                is PointRow -> PointLine(
                    id = row.id,
                    label = row.label,
                    age = ageText(nowMs - row.atMs),
                    changesAfter = rows.drop(i + 1).count { it is ChangeRow },
                    hearing = state.hearing,
                    actions = actions,
                    rolling = rolling == row.id,
                    onRolling = { rolling = it },
                    diamond = if (row.capture) MixerInk.accentSoft else MixerInk.changed,
                    border = if (row.capture) MixerInk.accentSoft else MixerInk.changed,
                )
            }
        }
        PointLine(
            id = MixHistory.FILE,
            label = "File",
            age = if (state.loadedAtMs > 0L) "loaded ${ageText(nowMs - state.loadedAtMs)}${if (nowMs - state.loadedAtMs < 60_000L) "" else " ago"}" else "",
            changesAfter = changeCount,
            hearing = state.hearing,
            actions = actions,
            rolling = rolling == MixHistory.FILE,
            onRolling = { rolling = it },
            last = state.beforeApply == null,
            diamond = MixerInk.dim,
        )
        state.beforeApply?.let { b ->
            val edits = if (b.changes == 0) "" else " · ${if (b.changes == 1) "1 mix change" else "${b.changes} mix changes"}"
            PointLine(
                id = MixHistory.BEFORE_APPLY,
                label = "Before ${b.who}'s apply: ${b.vibeName}",
                age = ageText(nowMs - b.atMs) + edits,
                changesAfter = changeCount,
                hearing = state.hearing,
                actions = actions,
                rolling = rolling == MixHistory.BEFORE_APPLY,
                onRolling = { rolling = it },
                last = true,
                diamond = MixerInk.dim,
                confirm = "Go back to ${b.vibeName} and its mix? The song restarts.",
            )
        }
        CaptureWrites(state.changes)
    }
}

/** A 44 dp gutter cell: the timeline's rail through [content], which sits on it. */
@Composable
private fun Gutter(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(width = NODE_TARGET, height = NODE_TARGET)
            .drawBehind {
                val x = size.width / 2
                val from = if (first) size.height / 2 else 0f
                val to = if (last) size.height / 2 else size.height
                drawLine(MixerInk.line, Offset(x, from), Offset(x, to), strokeWidth = 2.dp.toPx())
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ChangeLine(row: ChangeRow, nowMs: Long, actions: AiMixerActions) {
    val dim = !row.on
    val strike = if (dim) TextDecoration.LineThrough else null
    val text = buildAnnotatedString {
        append(row.label + "  ")
        withStyle(SpanStyle(color = if (dim) MixerInk.dim else MixerInk.red)) { append(row.before) }
        append(" → ")
        withStyle(SpanStyle(color = if (dim) MixerInk.dim else MixerInk.green)) { append(row.after) }
    }
    val ctx = row.where + " · " + ageText(nowMs - row.atMs) + if (dim) " · off" else ""
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Gutter(first = false, last = false, Modifier.fillMaxHeight()) {
            val verb = if (row.on) "Turn off" else "Turn on"
            Box(
                Modifier
                    .size(NODE_TARGET)
                    .testTag(historyNodeTag(row.id))
                    .clickable(role = Role.Switch) { actions.toggleChange(row.id) }
                    .semantics { contentDescription = "$verb ${row.label}" },
                contentAlignment = Alignment.Center,
            ) {
                if (row.on) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MixerInk.accent))
                } else {
                    Box(Modifier.size(8.dp).clip(CircleShape).border(2.dp, MixerInk.dim, CircleShape))
                }
            }
        }
        var open by remember(row.id) { mutableStateOf(false) }
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (dim) Modifier.border(1.dp, MixerInk.line, RoundedCornerShape(8.dp)) else Modifier.background(MixerInk.well))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                    WhoChip(row.who)
                    Text(
                        text,
                        style = mixText(legible(LegibleText.MIXER_MIN), if (dim) MixerInk.dim else MixerInk.text, mono = true)
                            .copy(textDecoration = strike),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 150.dp * textScale())) {
                    Text(ctx, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim), textAlign = TextAlign.End)
                    if (row.covered) {
                        Text("covered by a later change", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.changed), textAlign = TextAlign.End)
                    } else if (row.on) {
                        WritesToggle(row, open) { open = !open }
                    }
                    if (row.captured) Text("captured", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.accentSoft), textAlign = TextAlign.End)
                }
            }
            if (open && row.writes.isNotEmpty()) {
                ChangesList(remember(row.writes) { changeLines(row.writes) }, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

/** "writes N values": what Capture will commit for this change, opening the file values under the row. */
@Composable
private fun WritesToggle(row: ChangeRow, open: Boolean, onToggle: () -> Unit) {
    val n = row.writes.size
    if (n == 0) {
        Text("writes nothing", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim), textAlign = TextAlign.End)
        return
    }
    Text(
        (if (open) "▾ " else "▸ ") + "writes $n ${if (n == 1) "value" else "values"}",
        style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.accentSoft, FontWeight.SemiBold),
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier
            .testTag(historyWritesTag(row.id))
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun WhoChip(who: String) {
    val color = when (who) {
        "you" -> MixerInk.green
        "Orpheus" -> MixerInk.accentSoft
        else -> MixerInk.agent
    }
    Text(
        who,
        style = mixText(legible(LegibleText.MIXER_MIN), color, FontWeight.SemiBold),
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 5.dp),
    )
}

/** File or a marked point: hear while held, roll back after a confirm. */
@Composable
private fun PointLine(
    id: Int,
    label: String,
    age: String,
    changesAfter: Int,
    hearing: Int?,
    actions: AiMixerActions,
    rolling: Boolean,
    onRolling: (Int?) -> Unit,
    last: Boolean = false,
    diamond: Color,
    border: Color = if (id == MixHistory.FILE) MixerInk.line else MixerInk.changed,
    /** The rollback question; null asks to throw away the [changesAfter] changes. A point with its own text has no hear. */
    confirm: String? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Gutter(first = false, last, Modifier.fillMaxHeight()) {
            Box(Modifier.size(12.dp).rotate(45f).background(diamond))
        }
        Row(
            Modifier
                .weight(1f)
                .padding(vertical = 3.dp)
                .clip(shape)
                .background(MixerInk.card)
                .border(1.dp, border, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rolling) {
                val n = if (changesAfter == 1) "1 change" else "$changesAfter changes"
                Text(
                    confirm ?: "Throw away $n after this?",
                    style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.changed, FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                HistoryButton("Roll back", { onRolling(null); actions.rollBack(id) }, Modifier.testTag(historyConfirmTag(id)), warn = true)
                HistoryButton("Keep", { onRolling(null) }, Modifier.testTag(historyKeepTag(id)))
            } else {
                Column(Modifier.weight(1f)) {
                    Text(label, style = mixText(legible(LegibleText.MIXER_VALUE), MixerInk.text, FontWeight.Bold))
                    if (age.isNotEmpty()) Text(age, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim), maxLines = 1)
                }
                if (confirm == null) HearButton(id, hearing == id, actions)
                HistoryButton("roll back here", { onRolling(id) }, Modifier.testTag(historyRollBackTag(id)), warn = true)
            }
        }
    }
}

/** Holding plays the mix as it was at the point; letting go returns to Now. */
@Composable
private fun HearButton(id: Int, hearing: Boolean, actions: AiMixerActions) {
    val latest by rememberUpdatedState(actions)
    val shape = RoundedCornerShape(6.dp)
    Text(
        "hear",
        style = mixText(legible(LegibleText.MIXER_MIN), if (hearing) MixerInk.onBright else MixerInk.text),
        maxLines = 1,
        modifier = Modifier
            .testTag(historyHearTag(id))
            .clip(shape)
            .background(if (hearing) MixerInk.accent else MixerInk.chip)
            .border(1.dp, MixerInk.line, shape)
            .pointerInput(id) {
                detectTapGestures(onPress = {
                    latest.hear(id)
                    // The row can leave composition mid-press; endHear only queues, so it is safe from a cancelled gesture.
                    try {
                        tryAwaitRelease()
                    } finally {
                        latest.endHear()
                    }
                })
            }
            .semantics { contentDescription = "Hear the mix at this point while held" }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun HistoryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, warn: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        style = mixText(legible(LegibleText.MIXER_MIN), if (warn) MixerInk.changed else MixerInk.text),
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(MixerInk.chip)
            .border(1.dp, if (warn) MixerInk.changed else MixerInk.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun NowLine(detail: String) {
    val text = remember(detail) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MixerInk.text)) { append("Now") }
            withStyle(SpanStyle(color = MixerInk.dim)) { append(detail) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Gutter(first = true, last = false) {
            Canvas(Modifier.size(12.dp)) { drawCircle(MixerInk.green) }
        }
        Text(text, style = mixText(legible(LegibleText.MIXER_VALUE)))
    }
}

/** The file-value list Capture would write, collapsed under the timeline. */
@Composable
private fun CaptureWrites(changes: List<CaptureRow>) {
    var open by remember { mutableStateOf(false) }
    val lines = remember(changes) { changeLines(changes) }
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + "What Capture writes (${changes.size} file ${if (changes.size == 1) "value" else "values"})",
            style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim, FontWeight.SemiBold),
            modifier = Modifier.clickable(role = Role.Button) { open = !open }.padding(vertical = 4.dp),
        )
        if (open) ChangesList(lines, Modifier.fillMaxWidth())
    }
}

/** History over the strips, opened from the header's button. The caller sets width and height. */
@Composable
internal fun HistoryPopover(state: AiMixerUiState, actions: AiMixerActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val nowMs = rememberNowMs()
    // Focus comes here on open, so the panel's Esc handler sees the keys.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier
            .shadow(8.dp, shape)
            .clip(shape)
            .background(MixerInk.card)
            .border(1.dp, MixerInk.line, shape)
            // A pointer handler makes the card the hit target, so its taps never reach the close-on-tap scrim.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .focusRequester(focus)
            .focusable()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HistoryTitle(state, actions, onClose = onClose)
        // Fills the popover's fixed height, so it keeps one size however few rows there are.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            HistoryTimeline(state, actions, nowMs, Modifier.fillMaxWidth())
        }
        FaderNote()
    }
}
