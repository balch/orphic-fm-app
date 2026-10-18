package org.balch.orpheus.features.record

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.balch.orpheus.features.record.store.TakeInfo
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors

private val RecordAccent = OrpheusColors.recordRed
private val RowBackground = Color(0xFF151A3A)
private val MutedText = Color(0xFF7F93C9)

/** What the take list renders; level and elapsed time are collected separately at the leaves. */
private data class TakesView(
    val takes: List<TakeInfo>,
    val selectedTakeId: String?,
    val pendingDeleteId: String?,
)

private fun RecordUiState.toTakesView() = TakesView(takes, selectedTakeId, pendingDeleteId)

@Composable
fun RecordPanel(
    feature: RecordFeature,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val actions = feature.actions
    val takesView by remember(feature) {
        feature.stateFlow.map { it.toTakesView() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(feature.stateFlow.value.toTakesView())
    val recording by remember(feature) {
        feature.stateFlow.map { it.recording }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(feature.stateFlow.value.recording)

    CollapsibleColumnPanel(
        modifier = modifier,
        title = "REC",
        expandedTitle = "Remember {}",
        color = RecordAccent,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Row(
            modifier = Modifier.padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RecordControls(feature = feature, recording = recording, onToggle = actions.toggleRecord)
            TakesColumn(
                takes = takesView.takes,
                selectedTakeId = takesView.selectedTakeId,
                pendingDeleteId = takesView.pendingDeleteId,
                actions = actions,
                feature = feature,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RecordControls(
    feature: RecordFeature,
    recording: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecordButton(recording = recording, onClick = onToggle)
        ElapsedTimer(feature = feature, recording = recording)
        LevelMeter(feature = feature, modifier = Modifier.fillMaxWidth())
        Text(
            text = if (recording) "RECORDING" else "READY",
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = MutedText,
        )
    }
}

@Composable
private fun ElapsedTimer(feature: RecordFeature, recording: Boolean) {
    val elapsed by remember(feature) {
        feature.stateFlow.map { it.elapsedSec }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(feature.stateFlow.value.elapsedSec)
    Text(
        text = formatElapsed(elapsed),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = if (recording) RecordAccent else MutedText,
    )
}

/** Filled ring when idle, pulsing while recording. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .border(2.dp, RecordAccent.copy(alpha = if (recording) 1f else 0.35f), CircleShape)
            .background(OrpheusColors.panelSurfaceDark)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The pulse only exists while recording, so an idle panel runs no animation.
        if (recording) PulsingStopMark() else IdleRecordMark()
    }
}

@Composable
private fun IdleRecordMark() {
    Box(Modifier.size(34.dp).clip(CircleShape).background(RecordAccent.copy(alpha = 0.9f)))
}

@Composable
private fun PulsingStopMark() {
    val pulse by rememberInfiniteTransition(label = "recordPulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "recordPulseAlpha",
    )
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            // Read in the graphics layer so each frame redraws without recomposing.
            .graphicsLayer { alpha = pulse }
            .background(RecordAccent),
    )
}

@Composable
private fun LevelMeter(feature: RecordFeature, modifier: Modifier = Modifier) {
    val level by remember(feature) {
        feature.stateFlow.map { it.level }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(feature.stateFlow.value.level)
    val fill = level.coerceIn(0f, 1f)
    val color = when {
        fill > 0.9f -> RecordAccent
        fill > 0.7f -> OrpheusColors.ninersGold
        else -> OrpheusColors.synthGreen
    }
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(RowBackground),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(color))
    }
}

@Composable
private fun TakesColumn(
    takes: List<TakeInfo>,
    selectedTakeId: String?,
    pendingDeleteId: String?,
    actions: RecordPanelActions,
    feature: RecordFeature,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TAKES", fontSize = 10.sp, letterSpacing = 1.sp, color = MutedText)
        Column(
            modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (takes.isEmpty()) {
                Text("No takes yet", fontSize = 11.sp, color = MutedText)
            }
            takes.forEach { take ->
                TakeRow(
                    take = take,
                    selected = take.takeId == selectedTakeId,
                    confirmingDelete = take.takeId == pendingDeleteId,
                    actions = actions,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        takes.firstOrNull { it.takeId == selectedTakeId }?.let { ListenBar(feature, it, actions) }
    }
}

/** Play or pause, a progress line you click to seek, and `m:ss / m:ss`. Ticks are collected here. */
@Composable
private fun ListenBar(feature: RecordFeature, take: TakeInfo, actions: RecordPanelActions) {
    val playback by remember(feature) {
        feature.stateFlow.map { it.playback }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(feature.stateFlow.value.playback)
    // The player may still hold another take, so only show its progress for this one.
    val loaded = playback.takeId == take.takeId
    val playing = loaded && playback.playing
    val duration = if (loaded && playback.durationSec > 0f) playback.durationSec else take.durationSec
    val position = if (loaded) playback.positionSec else 0f
    val fraction = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (playing) {
            IconButton(Icons.Default.Pause, "Pause", OrpheusColors.neonCyan) { actions.pause() }
        } else {
            IconButton(Icons.Default.PlayArrow, "Play", OrpheusColors.neonCyan) { actions.play(take.takeId) }
        }
        ProgressLine(
            fraction = fraction,
            onSeek = { if (loaded) actions.seek(it) else actions.play(take.takeId) },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${formatClock(position)} / ${formatClock(duration)}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            color = MutedText,
        )
    }
}

@Composable
private fun ProgressLine(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    // A tall transparent hit area around a thin line keeps the click target easy to hit.
    val currentOnSeek by rememberUpdatedState(onSeek)
    Box(
        modifier = modifier
            .height(18.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentOnSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(RowBackground)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(OrpheusColors.neonCyan))
        }
    }
}

@Composable
private fun TakeRow(
    take: TakeInfo,
    selected: Boolean,
    confirmingDelete: Boolean,
    actions: RecordPanelActions,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RowBackground)
            .border(1.dp, if (selected) OrpheusColors.neonCyan else Color(0xFF23305E), shape)
            .clickable { actions.select(take.takeId) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(Icons.Default.PlayArrow, "Play", OrpheusColors.neonCyan) { actions.play(take.takeId) }
        Text(
            text = take.takeId,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            maxLines = 1,
            color = Color(0xFFE8F6FF),
        )
        if (confirmingDelete) {
            Text("delete?", fontSize = 10.sp, color = RecordAccent)
            TextButton("yes", RecordAccent) { actions.confirmDelete() }
            TextButton("no", MutedText) { actions.cancelDelete() }
        } else {
            Text(
                text = formatTakeMeta(take.durationSec, take.createdAtMs),
                fontSize = 10.sp,
                maxLines = 1,
                color = MutedText,
            )
            IconButton(Icons.Default.Close, "Delete", RecordAccent) { actions.requestDelete(take.takeId) }
        }
    }
}

@Composable
private fun IconButton(icon: ImageVector, description: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1D2550))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = color, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun TextButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

private val PreviewTakes = listOf(
    TakeInfo("take-2026-10-17-132431", 21.4f, 1_792_000_000_000L, ""),
    TakeInfo("take-2026-10-17-125812", 9.0f, 1_791_990_000_000L, ""),
    TakeInfo("take-2026-10-16-221004", 34.0f, 1_791_900_000_000L, ""),
)

@Preview(widthDp = 420, heightDp = 300)
@Composable
fun RecordPanelPreview() {
    LiquidPreviewContainerWithGradient {
        RecordPanel(
            feature = RecordViewModel.previewFeature(
                RecordUiState(takes = PreviewTakes, selectedTakeId = PreviewTakes.first().takeId, level = 0.55f),
            ),
            isExpanded = true,
        )
    }
}

@Preview(widthDp = 420, heightDp = 300)
@Composable
fun RecordPanelRecordingPreview() {
    LiquidPreviewContainerWithGradient {
        RecordPanel(
            feature = RecordViewModel.previewFeature(
                RecordUiState(
                    recording = true, elapsedSec = 12.4f, level = 0.8f, takes = PreviewTakes,
                    pendingDeleteId = PreviewTakes[1].takeId,
                ),
            ),
            isExpanded = true,
        )
    }
}
