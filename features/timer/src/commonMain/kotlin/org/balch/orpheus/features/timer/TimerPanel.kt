package org.balch.orpheus.features.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.features.timer.TimerLimits.MAX_HOURS
import org.balch.orpheus.features.timer.TimerLimits.MAX_MINUTES_AT_CAP_HOUR
import org.balch.orpheus.features.timer.TimerLimits.MAX_MINUTES_NORMAL
import org.balch.orpheus.features.timer.TimerLimits.MIN_HOURS
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.infrastructure.raisedAccentSurface
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Identifies one of [TimerPanel]'s transport buttons for its preview-only focus override. */
enum class TimerTransportButtonId { START_STOP, RESET }

@Composable
fun TimerPanel(
    feature: TimerFeature = TimerViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
    onExpandedChange: (Boolean) -> Unit = {},
    showCollapsedHeader: Boolean = true,
    showExpandedTitle: Boolean = true,
    fillHeight: Boolean = true,
    // Preview/render-harness seam only: forces one transport button's focus visual without a
    // real D-pad. Every production call site leaves this null.
    previewFocusedButton: TimerTransportButtonId? = null,
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    val isIdle = state.status == TimerStatus.IDLE || state.status == TimerStatus.FINISHED
    val glowColor = resolveGlowColor(state.status, state.remainingTime)

    // Split the current duration into HR and M for the two knobs. HR clamps
    // to [MIN_HOURS, MAX_HOURS]; M is the minute portion BEYOND full hours
    // (total minutes minus hour contribution) and clamps to
    // MAX_MINUTES_AT_CAP_HOUR when HR == MAX_HOURS, else MAX_MINUTES_NORMAL.
    // Using the modulo-style portion keeps the M knob steady when HR changes —
    // e.g. bumping HR 0 → 1 with M at 30 must leave M at 30, not snap to 59.
    val hoursDisplay = state.initialTime.inWholeHours.coerceIn(MIN_HOURS, MAX_HOURS)
    val maxMinutesForHour =
        if (hoursDisplay == MAX_HOURS) MAX_MINUTES_AT_CAP_HOUR else MAX_MINUTES_NORMAL
    val minutesDisplay = (state.initialTime.inWholeMinutes - hoursDisplay * 60L)
        .coerceIn(0L, maxMinutesForHour)

    CollapsibleColumnPanel(
        modifier = modifier,
        title = "Timer",
        expandedTitle = if (showExpandedTitle) "Sleep" else null,
        showCollapsedHeader = showCollapsedHeader,
        fillHeight = fillHeight,
        color = OrpheusColors.sleepMoonlight,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.status == TimerStatus.IDLE) {
                RotaryKnob(
                    modifier = Modifier.padding(top = 12.dp),
                    value = (hoursDisplay - MIN_HOURS).toFloat() / (MAX_HOURS - MIN_HOURS),
                    onValueChange = { v ->
                        val newHours = (v * (MAX_HOURS - MIN_HOURS)).roundToInt() + MIN_HOURS
                        val newMax =
                            if (newHours == MAX_HOURS) MAX_MINUTES_AT_CAP_HOUR else MAX_MINUTES_NORMAL
                        val clampedMinutes = minutesDisplay.coerceAtMost(newMax)
                        actions.onSetDuration(newHours.hours.plus(clampedMinutes.minutes))
                    },
                    size = 42.dp,
                    progressColor = OrpheusColors.sleepMoonlight,
                    enabled = isIdle,
                    valueFormatter = { v ->
                        "${(v * (MAX_HOURS - MIN_HOURS)).roundToInt() + MIN_HOURS}h"
                    },
                )
            }

            // Flip-clock countdown (read-only; knobs below own duration setting).
            FlipClockDisplay(
                remainingTime = state.remainingTime,
                isRunning = state.status == TimerStatus.RUNNING,
                isScrollable = state.status == TimerStatus.IDLE,
                digitHeight = 65.dp,
                glowColor = glowColor,
                onDurationChange = { actions.onSetDuration(it) },
                initialTime = state.initialTime,
            )

            if (state.status == TimerStatus.IDLE) {
                RotaryKnob(
                    modifier = Modifier.padding(top = 12.dp),
                    value = if (maxMinutesForHour == 0L) 0f
                    else minutesDisplay.toFloat() / maxMinutesForHour,
                    onValueChange = { v ->
                        val newMinutes = (v * maxMinutesForHour).roundToInt()
                        actions.onSetDuration(hoursDisplay.hours.plus(newMinutes.minutes))
                    },
                    size = 42.dp,
                    progressColor = OrpheusColors.sleepMoonlight,
                    enabled = isIdle,
                    valueFormatter = { v ->
                        "${(v * maxMinutesForHour).roundToInt()}m"
                    },
                )
            }
        }

        // Transport controls. Start/Stop carries its own RUNNING/PAUSED persistent wash — a
        // separate channel from D-pad focus, same docked-vs-focused split as DjTvBottomBar's
        // items, so a focused running timer never reads as a focused idle one. Reset is
        // momentary: focus is the only signal it ever needs.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isTransportActive = state.status == TimerStatus.RUNNING || state.status == TimerStatus.PAUSED
            TimerTransportButton(
                icon = if (isIdle) Icons.Default.PlayArrow else Icons.Default.Stop,
                contentDescription = if (isIdle) "Start" else "Stop",
                tint = OrpheusColors.sleepMoonlight,
                active = isTransportActive,
                previewFocused = previewFocusedButton == TimerTransportButtonId.START_STOP,
                onClick = {
                    when (state.status) {
                        TimerStatus.IDLE, TimerStatus.FINISHED -> actions.onStart()
                        TimerStatus.PAUSED -> actions.onStart()
                        else -> actions.onStop()
                    }
                },
            )

            Spacer(modifier = Modifier.width(8.dp))

            TimerTransportButton(
                icon = Icons.Default.Refresh,
                contentDescription = "Reset",
                tint = OrpheusColors.sleepMoonlight,
                previewFocused = previewFocusedButton == TimerTransportButtonId.RESET,
                onClick = { actions.onReset() },
            )
        }

        Text(
            text = state.status.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = OrpheusColors.sleepMoonlight.copy(alpha = 0.4f),
        )
    }
}

/**
 * Timer transport button (start/stop or reset). TV-gated raised-plate focus treatment, the same
 * language as [raisedAccentSurface]'s other call sites (DjTvBottomBar/DjTvTopBar, RotaryKnobDial).
 * [active] is a persistent state independent of focus (RUNNING/PAUSED for start/stop; always
 * false for the momentary Reset button) — the two read as separate signals, exactly like
 * DjTvBottomBar's docked-vs-focused split, so a focused running timer never looks confusably like
 * a focused idle one. Off TV ([LocalTvFocusChrome] false), the wrapping [Box] carries no modifier
 * at all, so this is pixel-identical to a plain [IconButton].
 */
@Composable
private fun TimerTransportButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    active: Boolean = false,
    previewFocused: Boolean = false,
) {
    val tvChrome = LocalTvFocusChrome.current
    val interactionSource = remember { MutableInteractionSource() }
    val liveFocused by interactionSource.collectIsFocusedAsState()
    val isFocused = tvChrome && (previewFocused || liveFocused)
    val shape = CircleShape

    Box(
        modifier = when {
            !tvChrome -> Modifier
            isFocused -> Modifier.raisedAccentSurface(accent = tint, shape = shape)
            active -> Modifier.clip(shape).background(tint.copy(alpha = 0.18f))
            else -> Modifier
        },
    ) {
        IconButton(onClick = onClick, interactionSource = interactionSource) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────

@Preview(name = "Timer Panel — 1h 30m Idle", heightDp = 280)
@Composable
private fun TimerPanelIdlePreview() {
    OrpheusTheme {
        TimerPanel(
            feature = TimerViewModel.previewFeature(
                TimerUiState(
                    initialTime = 90.minutes,
                    remainingTime = 90.minutes,
                    status = TimerStatus.IDLE,
                )
            ),
        )
    }
}

@Preview(name = "Timer Panel — 4h 20m Max", heightDp = 280)
@Composable
private fun TimerPanelMaxPreview() {
    OrpheusTheme {
        TimerPanel(
            feature = TimerViewModel.previewFeature(
                TimerUiState(
                    initialTime = 4.hours.plus(20.minutes),
                    remainingTime = 4.hours.plus(20.minutes),
                    status = TimerStatus.IDLE,
                )
            ),
        )
    }
}

@Preview(name = "Timer Panel — Running 45m", heightDp = 280)
@Composable
private fun TimerPanelRunningPreview() {
    OrpheusTheme {
        TimerPanel(
            feature = TimerViewModel.previewFeature(
                TimerUiState(
                    initialTime = 45.minutes,
                    remainingTime = 42.minutes.plus(13.seconds),
                    status = TimerStatus.RUNNING,
                )
            ),
        )
    }
}

@Preview(name = "Timer Panel — Final 8s", heightDp = 280)
@Composable
private fun TimerPanelFinalSecondsPreview() {
    OrpheusTheme {
        TimerPanel(
            feature = TimerViewModel.previewFeature(
                TimerUiState(
                    // 30-minute timer, 8 seconds left → SS countdown mode,
                    // glow deep into the ember lerp (t ≈ 0.87).
                    initialTime = 30.minutes,
                    remainingTime = 8.minutes,
                    status = TimerStatus.RUNNING,
                )
            ),
        )
    }
}

@Preview(name = "Timer Panel — Final 0s - IDLE", heightDp = 280)
@Composable
private fun TimerPanelFinalSecondsIdlePreview() {
    OrpheusTheme {
        TimerPanel(
            feature = TimerViewModel.previewFeature(
                TimerUiState(
                    // 30-minute timer, 8 seconds left → SS countdown mode,
                    // glow deep into the ember lerp (t ≈ 0.87).
                    initialTime = 0.minutes,
                    remainingTime = 0.minutes,
                    status = TimerStatus.IDLE,
                )
            ),
        )
    }
}

@Preview(name = "Timer Panel — Final 0s - IDLE", heightDp = 280)
@Composable
private fun TimerPanelFinalSecondsRunningPreview() {
    OrpheusTheme {
        TimerPanel(
            feature = TimerViewModel.previewFeature(
                TimerUiState(
                    // 30-minute timer, 8 seconds left → SS countdown mode,
                    // glow deep into the ember lerp (t ≈ 0.87).
                    initialTime = 0.minutes,
                    remainingTime = 0.minutes,
                    status = TimerStatus.RUNNING,
                )
            ),
        )
    }
}

@Preview(name = "Timer Panel — TV, Running, Stop focused", heightDp = 280)
@Composable
private fun TimerPanelTvStopFocusedPreview() {
    OrpheusTheme {
        CompositionLocalProvider(LocalTvFocusChrome provides true) {
            TimerPanel(
                feature = TimerViewModel.previewFeature(
                    TimerUiState(
                        initialTime = 45.minutes,
                        remainingTime = 42.minutes.plus(13.seconds),
                        status = TimerStatus.RUNNING,
                    )
                ),
                previewFocusedButton = TimerTransportButtonId.START_STOP,
            )
        }
    }
}

@Preview(name = "Timer Panel — TV, Idle, Reset focused", heightDp = 280)
@Composable
private fun TimerPanelTvResetFocusedPreview() {
    OrpheusTheme {
        CompositionLocalProvider(LocalTvFocusChrome provides true) {
            TimerPanel(
                feature = TimerViewModel.previewFeature(
                    TimerUiState(
                        initialTime = 90.minutes,
                        remainingTime = 90.minutes,
                        status = TimerStatus.IDLE,
                    )
                ),
                previewFocusedButton = TimerTransportButtonId.RESET,
            )
        }
    }
}
