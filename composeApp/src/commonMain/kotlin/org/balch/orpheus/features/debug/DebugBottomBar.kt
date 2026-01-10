package org.balch.orpheus.features.debug

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.util.LogEntry
import org.balch.orpheus.util.LogLevel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter
import kotlin.math.log10

/**
 * Entry point that obtains the ViewModel and delegates to the stateless composable.
 */
@Composable
fun DebugBottomBar(
    modifier: Modifier = Modifier
) {
    val viewModel: DebugViewModel = metroViewModel()
    val state by viewModel.stateFlow.collectAsState()
    
    DebugBottomBarContent(
        state = state,
        actions = viewModel.actions,
        modifier = modifier
    )
}

/**
 * Stateless composable that renders the debug bar.
 */
@Composable
fun DebugBottomBarContent(
    state: DebugUiState,
    actions: DebugPanelActions,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostSmall.dp,
                color = OrpheusColors.charcoal,
                tintAlpha = effects.tintAlpha,
                shape = shape
            )
            .border(width = 1.dp, color = OrpheusColors.lightShadow.copy(alpha = 0.5f), shape = shape)
            .animateContentSize()
            .clip(shape)
    ) {
        // Compact Row (Always Visible)
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            // Left: Status / Last Log
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 16.dp)
            ) {
                // Find last INFO or ERROR message (skip warnings and debug)
                val lastRelevantLog =
                    state.logs.firstOrNull { it.level == LogLevel.INFO || it.level == LogLevel.ERROR }
                val statusColor =
                    when (lastRelevantLog?.level) {
                        LogLevel.ERROR -> OrpheusColors.neonMagenta
                        else -> OrpheusColors.synthGreen
                    }

                Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = lastRelevantLog?.message ?: "System Ready",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.LightGray,
                    maxLines = 1,
                    fontWeight = FontWeight.Medium,
                    modifier =
                        Modifier.weight(1f, fill = false) // Allow text to shrink if needed
                )
            }

            // Right: Toggles & Expand
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // MONITORING METRICS

                // CPU Display
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU:", fontSize = 10.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${state.cpuLoad.toInt()}%",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color =
                            if (state.cpuLoad > 80) OrpheusColors.neonMagenta
                            else OrpheusColors.synthGreen
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // PEAK Display
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PEAK:", fontSize = 10.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    val peakDb = if (state.peak > 0) 20 * log10(state.peak) else -60f
                    val displayPeak = ((state.peak * 100).toInt() / 100.0).toString()

                    Text(
                        text = displayPeak,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color =
                            if (state.peak > 0.95f) Color.Red
                            else if (state.peak > 0.8f) Color.Yellow else OrpheusColors.electricBlue
                    )
                }

                // Expand Button
                Box(modifier = Modifier.clickable { isExpanded = !isExpanded }.padding(4.dp)) {
                    Text(text = if (isExpanded) "▼" else "▲", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }

        // Expanded Content (Logs List)
        if (isExpanded) {
            DebugLogsPanel(
                logs = state.logs,
                onClearLogs = actions.onClearLogs
            )
        }
    }
}

@Composable
private fun DebugLogsPanel(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = 200.dp)
                .background(OrpheusColors.darkVoid)
    ) {
        // Header with Clear button
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(OrpheusColors.charcoal)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Logs (${logs.size})", fontSize = 10.sp, color = Color.Gray)
            Text(
                text = "CLEAR",
                fontSize = 10.sp,
                color = OrpheusColors.neonMagenta,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onClearLogs() }.padding(4.dp)
            )
        }

        // Auto-scrolling log list
        val listState = rememberLazyListState()
        val logCount = logs.size

        // Auto-scroll to top (newest) when new logs arrive
        LaunchedEffect(logCount) {
            if (logCount > 0) {
                listState.animateScrollToItem(0)
            }
        }

        LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
            items(logs) { log ->
                val color =
                    when (log.level) {
                        LogLevel.ERROR -> OrpheusColors.neonMagenta
                        LogLevel.WARNING -> Color.Yellow
                        LogLevel.DEBUG -> Color.Gray
                        else -> OrpheusColors.synthGreen
                    }
                Text(
                    text = "[${log.timestamp % 10000}] ${log.message}",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

@Preview
@Composable
fun DebugBottomBarPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects
) {
    val previewState = DebugUiState(
        peak = 0.5f,
        cpuLoad = 12.5f,
        logs = listOf(
            LogEntry(LogLevel.INFO, "System initialized"),
            LogEntry(LogLevel.DEBUG, "Loading voice patches..."),
            LogEntry(LogLevel.WARNING, "Low battery detected (simulated)"),
            LogEntry(LogLevel.ERROR, "Failed to connect to external MIDI"),
            LogEntry(LogLevel.INFO, "Ready for performance")
        )
    )

    LiquidPreviewContainerWithGradient(effects = effects) {
        Box(modifier = Modifier.fillMaxSize()) {
            DebugBottomBarContent(
                state = previewState,
                actions = DebugPanelActions.EMPTY,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
