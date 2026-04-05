package org.balch.orpheus.features.timer

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun TimerPanel(
    feature: TimerFeature = TimerViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
    onExpandedChange: (Boolean) -> Unit = {},
    showCollapsedHeader: Boolean = true,
    showExpandedTitle: Boolean = true,
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    val isIdle = state.status == TimerStatus.IDLE || state.status == TimerStatus.FINISHED

    val glowColor = resolveGlowColor(state.status, state.remainingSeconds)

    CollapsibleColumnPanel(
        modifier = modifier,
        title = "Timer",
        expandedTitle = if (showExpandedTitle) "Sleep" else null,
        showCollapsedHeader = showCollapsedHeader,
        color = OrpheusColors.sleepMoonlight,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Compact flip clock
        FlipClockDisplay(
            remainingSeconds = state.remainingSeconds,
            isRunning = state.status == TimerStatus.RUNNING,
            isScrollable = isIdle,
            digitHeight = 48.dp,
            glowColor = glowColor,
            onDurationChange = { minutes -> actions.onSetDuration(minutes) },
            currentDurationMinutes = state.durationMinutes,
        )

        // Duration knob
        RotaryKnob(
            value = state.durationMinutes / 260f,
            onValueChange = { v -> actions.onSetDuration((v * 260f).toInt()) },
            label = "DURATION",
            size = 56.dp,
            progressColor = OrpheusColors.sleepMoonlight,
            enabled = isIdle,
            valueFormatter = { v ->
                val minutes = (v * 260f).toInt()
                "${minutes}m"
            },
        )

        // Transport controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Play/Stop button
            IconButton(
                onClick = {
                    when (state.status) {
                        TimerStatus.IDLE, TimerStatus.FINISHED -> actions.onStart()
                        TimerStatus.PAUSED -> actions.onStart()
                        else -> actions.onStop()
                    }
                }
            ) {
                val icon = when (state.status) {
                    TimerStatus.IDLE, TimerStatus.FINISHED -> Icons.Default.PlayArrow
                    else -> Icons.Default.Stop
                }
                Icon(
                    imageVector = icon,
                    contentDescription = if (isIdle) "Start" else "Stop",
                    tint = OrpheusColors.sleepMoonlight,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Reset button
            IconButton(
                onClick = { actions.onReset() },
                enabled = state.status != TimerStatus.IDLE,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset",
                    tint = OrpheusColors.sleepMoonlight.copy(
                        alpha = if (state.status != TimerStatus.IDLE) 1f else 0.3f
                    ),
                )
            }
        }

        // Status label
        Text(
            text = state.status.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = OrpheusColors.sleepMoonlight.copy(alpha = 0.4f),
        )
    }
}
