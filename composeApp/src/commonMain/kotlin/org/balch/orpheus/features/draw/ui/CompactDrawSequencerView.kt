package org.balch.orpheus.features.draw.ui

/**
 * Compact inline view for multi-parameter sequencer automation.
 *
 * Shows:
 * - Play/Pause toggle button
 * - Stop button
 * - Time remaining display
 * - Mini sequencer preview with all paths (click to expand)
 * - Clickable legend for parameter identification
 */
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.features.draw.DrawSequencerConfig
import org.balch.orpheus.features.draw.DrawSequencerFeature
import org.balch.orpheus.features.draw.DrawSequencerParameter
import org.balch.orpheus.features.draw.DrawSequencerPlaybackMode
import org.balch.orpheus.features.draw.DrawSequencerState
import org.balch.orpheus.features.draw.DrawSequencerUiState
import org.balch.orpheus.features.draw.DrawSequencerViewModel
import org.balch.orpheus.features.draw.SequencerPath
import org.balch.orpheus.features.draw.SequencerPoint
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects

/**
 * Compact inline view for multi-parameter sequencer automation.
 *
 * Shows:
 * - Play/Pause toggle button
 * - Stop button
 * - Time remaining display
 * - Mini sequencer preview with all paths (click to expand)
 * - Clickable legend for parameter identification
 */
@Composable
fun CompactDrawSequencerView(
    sequencerFeature: DrawSequencerFeature = DrawSequencerViewModel.feature(),
    liquidState: LiquidState?,
    effects: VisualizationLiquidEffects,
    modifier: Modifier = Modifier
) {
    val uiState by sequencerFeature.stateFlow.collectAsState()
    val actions = sequencerFeature.actions
    val state = uiState.sequencer

    val shape = RoundedCornerShape(8.dp)
    val isActive = state.config.enabled
    val accentColor = OrpheusColors.neonCyan
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .fillMaxSize()
                .background(OrpheusColors.panelBackground.copy(alpha = 0.8f))
                .border(1.dp, accentColor.copy(alpha = if (isActive) 0.5f else 0.2f), shape)
                .padding(8.dp),
        ) {
            // Controls Column: Buttons top, Time bottom
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                // Transport Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Play/Pause button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (isActive) accentColor.copy(alpha = 0.3f) else OrpheusColors.panelSurface)
                            .border(1.dp, accentColor.copy(alpha = 0.5f), CircleShape)
                            .clickable(enabled = isActive) { actions.onTogglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (state.isPlaying) "⏸" else "▶",
                            fontSize = 14.sp,
                            color = if (isActive) accentColor else accentColor.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center
                        )
                    }

                    // Stop button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(OrpheusColors.panelSurface)
                            .border(1.dp, accentColor.copy(alpha = 0.3f), CircleShape)
                            .clickable(enabled = isActive && (state.isPlaying || state.currentPosition > 0f)) { actions.onStop() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⏹",
                            fontSize = 12.sp,
                            color = if (isActive) accentColor.copy(alpha = 0.7f) else accentColor.copy(alpha = 0.3f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Playback mode selector (tap to cycle) - directly under transport buttons
                val (modeIcon, modeLabel) = when (state.config.drawSequencerPlaybackMode) {
                    DrawSequencerPlaybackMode.ONCE -> "→|" to "Once"
                    DrawSequencerPlaybackMode.LOOP -> "↻" to "Loop"
                    DrawSequencerPlaybackMode.PING_PONG -> "↔" to "P-P"
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .clickable {
                            val nextMode = when (state.config.drawSequencerPlaybackMode) {
                                DrawSequencerPlaybackMode.ONCE -> DrawSequencerPlaybackMode.LOOP
                                DrawSequencerPlaybackMode.LOOP -> DrawSequencerPlaybackMode.PING_PONG
                                DrawSequencerPlaybackMode.PING_PONG -> DrawSequencerPlaybackMode.ONCE
                            }
                            actions.onSetPlaybackMode(nextMode)
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = modeIcon,
                        fontSize = 10.sp,
                        color = accentColor
                    )
                    Text(
                        text = modeLabel,
                        fontSize = 8.sp,
                        color = accentColor.copy(alpha = 0.8f)
                    )
                }

                // Time/Param display
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val remainingSeconds = ((1f - state.currentPosition) * state.config.durationSeconds).toInt()
                    val minutes = remainingSeconds / 60
                    val seconds = remainingSeconds % 60

                    Text(
                        text = "$minutes:${seconds.toString().padStart(2, '0')}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "${state.config.selectedParameters.size} params",
                        fontSize = 8.sp,
                        color = accentColor.copy(alpha = 0.7f)
                    )
                }
            }

            // Mini sequencer preview (tap to expand)
            MultiPathSequencerPreview(
                paths = state.paths,
                currentPosition = state.currentPosition,
                enabled = isActive,
                liquidState = liquidState,
                effects = effects,
                onClick = { actions.onExpand() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Miniature sequencer preview showing all paths with their colors.
 */
@Composable
private fun MultiPathSequencerPreview(
    paths: Map<DrawSequencerParameter, SequencerPath>,
    currentPosition: Float,
    enabled: Boolean,
    liquidState: LiquidState?,
    effects: VisualizationLiquidEffects,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .then(
                if (liquidState != null) {
                    Modifier.liquidVizEffects(
                        liquidState = liquidState,
                        scope = effects.bottom,
                        frostAmount = 4.dp,
                        color = OrpheusColors.deepSpaceDark,
                        shape = shape
                    )
                } else Modifier.background(OrpheusColors.deepSpaceDark)
            )
            .border(1.dp, OrpheusColors.neonCyan.copy(alpha = if (enabled) 0.3f else 0.1f), shape)
            .clickable { onClick() }  // Always clickable so user can expand to configure
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Draw each path with its parameter's color
            paths.forEach { (param, path) ->
                if (path.points.isNotEmpty()) {
                    val pathDraw = Path()
                    var first = true
                    for (point in path.points) {
                        val x = point.time * width
                        val y = (1f - point.value) * height
                        if (first) {
                            pathDraw.moveTo(x, y)
                            first = false
                        } else {
                            pathDraw.lineTo(x, y)
                        }
                    }
                    drawPath(
                        path = pathDraw,
                        color = param.color.copy(alpha = if (enabled) param.color.alpha else param.color.alpha * 0.5f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Draw playhead
            val playheadX = currentPosition * width
            drawLine(
                color = Color.White.copy(alpha = if (enabled) 0.8f else 0.3f),
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

/**
 * Clickable legend showing parameter names and colors.
 */
@Composable
private fun ParameterLegend(
    parameters: List<DrawSequencerParameter>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(OrpheusColors.panelBackground.copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        parameters.forEach { param ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(param.color)
                )
                Text(
                    text = param.label,
                    fontSize = 9.sp,
                    color = param.color,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Preview(heightDp = 240)
@Composable
private fun CompactTweakSequencerViewPreview() {
    val samplePath = SequencerPath(
        points = listOf(
            SequencerPoint(0f, 0.5f),
            SequencerPoint(0.3f, 0.8f),
            SequencerPoint(0.6f, 0.2f),
            SequencerPoint(1f, 0.6f)
        ),
        isComplete = true
    )

    val state = DrawSequencerUiState(
        sequencer = DrawSequencerState(
            config = DrawSequencerConfig(
                enabled = true,
                selectedParameters = listOf(
                    DrawSequencerParameter.LFO_FREQ_A,
                    DrawSequencerParameter.DELAY_TIME_1,
                    DrawSequencerParameter.DIST_DRIVE
                )
            ),
            paths = mapOf(
                DrawSequencerParameter.LFO_FREQ_A to samplePath,
                DrawSequencerParameter.DELAY_TIME_1 to SequencerPath(
                    points = listOf(SequencerPoint(0f, 0.2f), SequencerPoint(1f, 0.9f)),
                    isComplete = true
                ),
                DrawSequencerParameter.DIST_DRIVE to SequencerPath()
            ),
            currentPosition = 0.4f,
            isPlaying = true
        )
    )

    CompactDrawSequencerView(
        sequencerFeature = DrawSequencerViewModel.previewFeature(state),
        liquidState = null,
        effects = VisualizationLiquidEffects(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Preview(heightDp = 240)
@Composable
private fun CompactTweakSequencerViewDisabledPreview() {
    val state = DrawSequencerUiState(
        sequencer = DrawSequencerState(
            config = DrawSequencerConfig(enabled = false),
            paths = emptyMap(),
            currentPosition = 0f,
            isPlaying = false
        )
    )

    CompactDrawSequencerView(
        sequencerFeature = DrawSequencerViewModel.previewFeature(state),
        liquidState = null,
        effects = VisualizationLiquidEffects(),
        modifier = Modifier.fillMaxWidth()
    )
}
