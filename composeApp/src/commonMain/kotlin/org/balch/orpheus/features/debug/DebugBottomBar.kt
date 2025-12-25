package org.balch.orpheus.features.debug

// Imports removed
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.util.LogLevel
import org.balch.orpheus.util.Logger
import kotlin.math.log10

@Composable
fun DebugBottomBar(engine: SynthEngine, modifier: Modifier = Modifier) {
    var isExpanded by remember { mutableStateOf(false) }

    // Toggles
    var showVisualizer by remember { mutableStateOf(false) }
    var logAudioEvents by remember { mutableStateOf(true) }

    // Collect logs from StateFlow
    val logs by Logger.logsFlow.collectAsState()

    Column(
        modifier =
            modifier.fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                // Removed specific border(top=...) logic as standard border is all
                // sides.
                // Using a simple top-padding or box if needed, or just full border.
                // For now simple border.
                .border(width = 1.dp, color = Color(0xFF333333))
                .animateContentSize()
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
                    logs.firstOrNull { it.level == LogLevel.INFO || it.level == LogLevel.ERROR }
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

                // MONITORING METRICS
                Spacer(modifier = Modifier.width(16.dp))

                // Collect from engine flows
                val peak by engine.peakFlow.collectAsState()
                val cpu by engine.cpuLoadFlow.collectAsState()

                // CPU Display
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU:", fontSize = 10.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${cpu.toInt()}%",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color =
                            if (cpu > 80) OrpheusColors.neonMagenta
                            else OrpheusColors.synthGreen
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // PEAK Display
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PEAK:", fontSize = 10.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    val peakDb = if (peak > 0) 20 * log10(peak) else -60f
                    val displayPeak = ((peak * 100).toInt() / 100.0).toString()

                    Text(
                        text = displayPeak,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color =
                            if (peak > 0.95f) Color.Red
                            else if (peak > 0.8f) Color.Yellow else OrpheusColors.electricBlue
                    )
                }
            }

            // Right: Toggles & Expand
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Toggles
                DebugToggle(
                    label = "Log Audio",
                    checked = logAudioEvents,
                    onCheckedChange = { logAudioEvents = it }
                )
                DebugToggle(
                    label = "Visuals (Stub)",
                    checked = showVisualizer,
                    onCheckedChange = { showVisualizer = it }
                )

                // Audio Test (Quick Action)
                var isTestTonePlaying by remember { mutableStateOf(false) }
                Box(
                    modifier =
                        Modifier.clickable {
                            isTestTonePlaying = !isTestTonePlaying
                            if (isTestTonePlaying) {
                                engine.playTestTone(440f)
                            } else {
                                engine.stopTestTone()
                            }
                        }
                            .background(
                                if (isTestTonePlaying)
                                    OrpheusColors.neonCyan.copy(alpha = 0.3f)
                                else Color(0xFF333333),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isTestTonePlaying) "STOP TONE" else "TEST TONE",
                        fontSize = 10.sp,
                        color = if (isTestTonePlaying) OrpheusColors.neonCyan else Color.Gray
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
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(Color(0xFF0D0D0D))
            ) {
                // Header with Clear button
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF1A1A1A))
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
                        modifier = Modifier.clickable { Logger.clear() }.padding(4.dp)
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
    }
}

@Composable
private fun DebugToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        Spacer(modifier = Modifier.width(4.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.6f),
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = OrpheusColors.neonCyan,
                    checkedTrackColor = OrpheusColors.neonCyan.copy(alpha = 0.3f),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.DarkGray
                )
        )
    }
}
