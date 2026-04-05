package org.balch.orpheus.features.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.dialogs.DraggableDialog

// Compact threshold mirrors SynthScreen.determineLayoutMode
private fun isCompact(widthDp: Float, heightDp: Float) = widthDp < 600f || heightDp < 400f
private fun isLandscape(widthDp: Float, heightDp: Float) = widthDp > heightDp

@Composable
fun TimerOverlay(
    feature: TimerFeature,
    modifier: Modifier = Modifier,
    liquidState: LiquidState,
) {
    val state by feature.stateFlow.collectAsState()

    if (!state.showOverlay) return

    BoxWithConstraints(modifier = modifier) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value

        when {
            // Compact portrait — panel's FlipClockDisplay already shows countdown in-place
            isCompact(widthDp, heightDp) && !isLandscape(widthDp, heightDp) -> {
                // No overlay rendered; panel handles it
            }
            // Compact landscape — fullscreen flip clock covers entire screen
            isCompact(widthDp, heightDp) && isLandscape(widthDp, heightDp) -> {
                TimerFullscreen(
                    feature = feature,
                    onDismiss = feature.actions.onToggleOverlay,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Medium/Expanded (tablet/desktop) — draggable dialog anchored bottom-end
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    TimerDraggableDialog(
                        feature = feature,
                        modifier = Modifier.padding(end = 16.dp, bottom = 60.dp),
                        liquidState = liquidState,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerDraggableDialog(
    feature: TimerFeature,
    modifier: Modifier = Modifier,
    liquidState: LiquidState,
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    val glowColor = resolveGlowColor(state.status, state.remainingSeconds)
    val totalSeconds = state.durationMinutes * 60L
    val elapsedProgress = calcElapsedProgress(state, totalSeconds)

    DraggableDialog(
        title = "SLEEP TIMER",
        showAvatar = false,
        onClose = actions.onToggleOverlay,
        position = state.overlayPosition,
        onPositionChange = actions.onOverlayPositionChange,
        size = state.overlaySize,
        onSizeChange = actions.onOverlaySizeChange,
        minWidth = 350.dp,
        minHeight = 280.dp,
        backgroundColor = OrpheusColors.sleepIndigo,
        borderColor = OrpheusColors.sleepDusk.copy(alpha = 0.4f),
        modifier = modifier,
        liquidState = liquidState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Large flip clock
            FlipClockDisplay(
                remainingSeconds = state.remainingSeconds,
                isRunning = state.status == TimerStatus.RUNNING,
                isScrollable = false,
                digitHeight = 108.dp,
                glowColor = glowColor,
            )

            // Labels row: HOURS and MINUTES
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Text(
                    text = "HOURS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = OrpheusColors.sleepMoonlight.copy(alpha = 0.3f),
                )
                Text(
                    text = "MINUTES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = OrpheusColors.sleepMoonlight.copy(alpha = 0.3f),
                )
            }

            // Transport controls
            TimerTransportRow(state = state, actions = actions, glowColor = glowColor)

            // Progress bar
            LinearProgressIndicator(
                progress = { elapsedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = glowColor,
                trackColor = OrpheusColors.sleepIndigo,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )

            // Time labels under progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "0:00",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = OrpheusColors.sleepMoonlight.copy(alpha = 0.5f),
                )
                Text(
                    text = formatTime(totalSeconds),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = OrpheusColors.sleepMoonlight.copy(alpha = 0.5f),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Fullscreen timer display for compact landscape (phone in landscape).
 *
 * Fills the screen with a near-black background. Shows a large flip clock
 * centered, transport controls below, and a progress bar at the bottom.
 * Tapping anywhere on the background dismisses the fullscreen view while
 * keeping the timer running.
 */
@Composable
fun TimerFullscreen(
    feature: TimerFeature,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    val glowColor = resolveGlowColor(state.status, state.remainingSeconds)
    val totalSeconds = state.durationMinutes * 60L
    val elapsedProgress = calcElapsedProgress(state, totalSeconds)

    // Tap on background dismisses fullscreen; inner controls don't propagate
    val bgInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable(
                interactionSource = bgInteraction,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 16.dp)
                // Consume clicks so they don't bubble to the dismiss handler
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            FlipClockDisplay(
                remainingSeconds = state.remainingSeconds,
                isRunning = state.status == TimerStatus.RUNNING,
                isScrollable = false,
                digitHeight = 140.dp,
                glowColor = glowColor,
            )

            // Transport controls
            TimerTransportRow(state = state, actions = actions, glowColor = glowColor)

            // Progress bar
            LinearProgressIndicator(
                progress = { elapsedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = glowColor,
                trackColor = OrpheusColors.sleepIndigo,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )

            // Time labels under progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "0:00",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = OrpheusColors.sleepMoonlight.copy(alpha = 0.5f),
                )
                Text(
                    text = formatTime(totalSeconds),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = OrpheusColors.sleepMoonlight.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ─── Shared helpers ───────────────────────────────────────────────────────────

@Composable
private fun TimerTransportRow(
    state: TimerUiState,
    actions: TimerActions,
    glowColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val canPauseResume =
            state.status == TimerStatus.RUNNING || state.status == TimerStatus.PAUSED
        IconButton(
            onClick = {
                if (state.status == TimerStatus.RUNNING) actions.onPause()
                else if (state.status == TimerStatus.PAUSED) actions.onStart()
            },
            enabled = canPauseResume,
        ) {
            val icon =
                if (state.status == TimerStatus.RUNNING) Icons.Default.Pause
                else Icons.Default.PlayArrow
            Icon(
                imageVector = icon,
                contentDescription = if (state.status == TimerStatus.RUNNING) "Pause" else "Resume",
                tint = glowColor.copy(alpha = if (canPauseResume) 1f else 0.3f),
            )
        }

        val canStop = state.status != TimerStatus.IDLE
        IconButton(
            onClick = actions.onStop,
            enabled = canStop,
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop",
                tint = glowColor.copy(alpha = if (canStop) 1f else 0.3f),
            )
        }

        val canReset = state.status != TimerStatus.IDLE
        IconButton(
            onClick = actions.onReset,
            enabled = canReset,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reset",
                tint = glowColor.copy(alpha = if (canReset) 1f else 0.3f),
            )
        }
    }
}

private fun calcElapsedProgress(state: TimerUiState, totalSeconds: Long): Float =
    if (totalSeconds > 0L) {
        1f - (state.remainingSeconds.toFloat() / totalSeconds.toFloat())
    } else {
        0f
    }
