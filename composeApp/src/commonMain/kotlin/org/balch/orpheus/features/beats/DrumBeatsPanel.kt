package org.balch.orpheus.features.beats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.dsp.synth.DrumBeatsGenerator
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.HorizontalMiniSlider
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun DrumBeatsPanel(
    drumBeatsFeature: DrumBeatsFeature = DrumBeatsViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val state by drumBeatsFeature.stateFlow.collectAsState()
    val actions = drumBeatsFeature.actions

    val outputMode = state.outputMode

    CollapsibleColumnPanel(
        title = "BEATS",
        color = OrpheusColors.seahawksGreen,
        expandedTitle = "Drums & Space",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        // Center and constrain width
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top
            ) {
                // LEFT: Mode Switcher + Visualization
                Column(
                    modifier = Modifier.width(140.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Two-state toggle: Grids | Euclid
                    ModeToggle(
                        currentMode = outputMode,
                        onModeSelected = actions.setOutputMode
                    )

                    // X/Y Pad or Lengths visualization
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .background(OrpheusColors.seahawksNavy, shape = RoundedCornerShape(4.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .clip(RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (outputMode == DrumBeatsGenerator.OutputMode.DRUMS) {
                            // X/Y Pad
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures { offset ->
                                            actions.setX(offset.x / size.width)
                                            actions.setY(offset.y / size.height)
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, _ ->
                                            actions.setX(change.position.x / size.width)
                                            actions.setY(change.position.y / size.height)
                                        }
                                    }
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    // Grid lines
                                    for (i in 1..4) {
                                        val pos = i * size.width / 5
                                        drawLine(Color.White.copy(alpha = 0.1f), Offset(pos, 0f), Offset(pos, size.height))
                                        drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, pos), Offset(size.width, pos))
                                    }

                                    // Crosshair
                                    drawCircle(
                                        color = OrpheusColors.seahawksGreen,
                                        radius = 6.dp.toPx(),
                                        center = Offset(state.x * size.width, state.y * size.height)
                                    )
                                }
                            }
                        } else {
                            // Euclidean Lengths
                            Column(
                                verticalArrangement = Arrangement.SpaceEvenly,
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                val colors = listOf(OrpheusColors.seahawksGreen, OrpheusColors.seahawksGrey, Color.White)
                                state.euclideanLengths.forEachIndexed { index, len ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                         Text("L${index+1}", style = MaterialTheme.typography.labelSmall, color = colors[index])
                                         RotaryKnob(
                                            value = len / 32f,
                                            onValueChange = { actions.setEuclideanLength(index, (it * 32).toInt()) },
                                            size = 24.dp,
                                            progressColor = colors[index]
                                         )
                                         Text("$len", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }

                // RIGHT: Controls + Transport
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Row 1: BD, SD, HH
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween 
                    ) {
                         KnobControlTopLabel(
                            modifier = Modifier.padding(top = 4.dp),
                            label = "BD",
                            value = state.densities[0],
                            onValueChange = { actions.setDensity(0, it) },
                            color = OrpheusColors.seahawksGreen
                        )
                        KnobControlTopLabel(
                            modifier = Modifier.padding(top = 42.dp),
                            label = "CHAOS",
                            value = state.randomness,
                            onValueChange = { actions.setRandomness(it) },
                            color = OrpheusColors.seahawksGreen
                        )
                        KnobControlTopLabel(
                            modifier = Modifier.padding(top = 4.dp),
                            label = "SD",
                            value = state.densities[1],
                            onValueChange = { actions.setDensity(1, it) },
                            color = OrpheusColors.seahawksGrey
                        )
                        KnobControlTopLabel(
                            modifier = Modifier.padding(top = 42.dp),
                            label = "SWING",
                            value = state.swing,
                            onValueChange = { actions.setSwing(it) },
                            color = OrpheusColors.seahawksGreen
                        )
                        KnobControlTopLabel(
                            modifier = Modifier.padding(top = 4.dp),
                            label = "HH",
                            value = state.densities[2],
                            onValueChange = { actions.setDensity(2, it) },
                            color = Color.White
                        )
                    }

                    // Row 3: Transport (Start/Stop, Tap Tempo)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // BPM Control
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            HorizontalMiniSlider(
                                trackWidth = 80,
                                value = ((state.bpm - 40) / 200).coerceIn(0f, 1f),
                                onValueChange = { frac ->
                                    actions.setBpm(40f + (frac * 200f))
                                },
                                color = OrpheusColors.seahawksGreen
                            )
                            Text(
                                text = "${state.bpm.toInt()}bmp",
                                style = MaterialTheme.typography.labelSmall,
                                color = OrpheusColors.seahawksGreen,
                                fontSize = 9.sp,
                                maxLines = 1,
                            )
                        }

                        // RUN/STOP
                        IconButton(
                            onClick = { actions.setRunning(!state.isRunning) },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.isRunning) OrpheusColors.seahawksGreen else Color.White.copy(alpha = 0.2f)
                                )
                        ) {
                            Icon(
                                imageVector = if (state.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (state.isRunning) "Stop" else "Start",
                                tint = if (state.isRunning) Color.Black else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeToggle(
    currentMode: DrumBeatsGenerator.OutputMode,
    onModeSelected: (DrumBeatsGenerator.OutputMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                 val newMode = if (currentMode == DrumBeatsGenerator.OutputMode.DRUMS) 
                    DrumBeatsGenerator.OutputMode.EUCLIDEAN 
                 else 
                    DrumBeatsGenerator.OutputMode.DRUMS
                 onModeSelected(newMode)
            }
    ) {
        // Grids (Drums)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    if (currentMode == DrumBeatsGenerator.OutputMode.DRUMS) OrpheusColors.seahawksGreen else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Grid",
                style = MaterialTheme.typography.labelSmall,
                color = if (currentMode == DrumBeatsGenerator.OutputMode.DRUMS) Color.Black else Color.White
            )
        }
        
        // Euclid
        Box(
             modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    if (currentMode == DrumBeatsGenerator.OutputMode.EUCLIDEAN) OrpheusColors.seahawksGreen else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Euclid", 
                style = MaterialTheme.typography.labelSmall,
                color = if (currentMode == DrumBeatsGenerator.OutputMode.EUCLIDEAN) Color.Black else Color.White
            )
        }
    }
}

@Composable
private fun KnobControlTopLabel(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        RotaryKnob(
            value = value,
            onValueChange = onValueChange,
            size = 32.dp, // Reduced size
            progressColor = color
        )
    }
}

@Preview(heightDp = 280, widthDp = 420)
@Composable
fun PatternPanelPreview() {
    OrpheusTheme {
        DrumBeatsPanel(
        drumBeatsFeature = DrumBeatsViewModel.previewFeature(),
        isExpanded = true
        )
    }
}
