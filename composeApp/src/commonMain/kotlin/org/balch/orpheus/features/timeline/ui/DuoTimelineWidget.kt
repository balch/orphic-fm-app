package org.balch.orpheus.features.timeline.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.timeline.DuoTimelineViewModel
import org.balch.orpheus.features.timeline.TimelineTarget
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Smart wrapper that connects DuoTimelineViewModel to UI components.
 *
 * Usage:
 * ```
 * DuoTimelineWidget(
 *     target = TimelineTarget.LFO,
 *     color = OrpheusColors.neonCyan,
 *     modifier = Modifier.fillMaxWidth()
 * )
 * ```
 *
 * @param target Which parameter pair this widget controls
 * @param color Accent color for the widget
 */
@Composable
fun DuoTimelineWidget(
    target: TimelineTarget,
    color: Color = OrpheusColors.neonCyan,
    viewModel: DuoTimelineViewModel = metroViewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val timelineState = state.timelines[target] ?: return

    Box(modifier = modifier) {
        // Compact view always visible
        CompactTimelineView(
            state = timelineState,
            color = color,
            onPlayPause = {
                if (timelineState.isPlaying) {
                    viewModel.pause(target)
                } else {
                    viewModel.play(target)
                }
            },
            onStop = { viewModel.stop(target) },
            onExpand = { viewModel.expandTimeline(target) },
            modifier = Modifier.fillMaxWidth()
        )

        // Expanded editor dialog
        if (state.expandedTarget == target) {
            Dialog(onDismissRequest = { viewModel.cancel(target) }) {
                ExpandedTimelineScreen(
                    target = target,
                    state = timelineState,
                    selectedParam = state.selectedParam,
                    color = color,
                    onDurationChange = { viewModel.setDuration(target, it) },
                    onPlaybackModeChange = { viewModel.setPlaybackMode(target, it) },
                    onEnabledChange = { viewModel.setEnabled(target, it) },
                    onParamSelect = { viewModel.selectParam(it) },
                    onPathStarted = { param, point -> viewModel.startPath(target, param, point) },
                    onPointAdded = { param, point -> viewModel.addPoint(target, param, point) },
                    onPointsRemovedAfter = { param, time -> viewModel.removePointsAfter(target, param, time) },
                    onPathCompleted = { param, value -> viewModel.completePath(target, param, value) },
                    onClearPath = { viewModel.clearPath(target, it) },
                    onSave = { viewModel.save(target) },
                    onCancel = { viewModel.cancel(target) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
        }
    }
}

/**
 * Stateless version for previews and testing.
 */
@Composable
fun DuoTimelineWidgetLayout(
    target: TimelineTarget,
    state: org.balch.orpheus.features.timeline.DuoTimelineUiState,
    color: Color,
    onPlayPause: (TimelineTarget) -> Unit,
    onStop: (TimelineTarget) -> Unit,
    onExpand: (TimelineTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val timelineState = state.timelines[target] ?: return

    CompactTimelineView(
        state = timelineState,
        color = color,
        onPlayPause = { onPlayPause(target) },
        onStop = { onStop(target) },
        onExpand = { onExpand(target) },
        modifier = modifier
    )
}
