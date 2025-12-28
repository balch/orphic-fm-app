package org.balch.orpheus.features.tidal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.balch.orpheus.features.tidal.LiveCodeViewModel
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.HorizontalMiniSlider
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Collapsible live coding panel for the header row.
 * 
 * When collapsed: Shows "CODE" title vertically
 * When expanded: Shows "Tidal Live Coding" header with code editor
 */
@Composable
fun LiveCodePanel(
    modifier: Modifier = Modifier,
    viewModel: LiveCodeViewModel = metroViewModel(),
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    
    // Track active highlight ranges for token highlighting
    var activeHighlights by remember { mutableStateOf(listOf<IntRange>()) }
    val scope = rememberCoroutineScope()
    
    // Subscribe to trigger events for token highlighting
    LaunchedEffect(Unit) {
        viewModel.triggers.collect { triggerEvent ->
            // Launch each highlight lifecycle independently
            val newRanges = triggerEvent.locations.map { loc -> loc.start until loc.end }
            activeHighlights = activeHighlights + newRanges
            
            // Launch coroutine to clear this highlight after 250ms (doesn't block collect)
            scope.launch {
                kotlinx.coroutines.delay(250)
                activeHighlights = activeHighlights - newRanges.toSet()
            }
        }
    }
    
    LiveCodePanelLayout(
        code = state.code,
        onCodeChange = viewModel::updateCode,
        onExecuteBlock = viewModel::executeBlock,
        onExecuteLine = viewModel::executeLine,
        isPlaying = state.isPlaying,
        currentCycle = state.currentCycle,
        cyclePosition = state.cyclePosition,
        bpm = state.bpm,
        onBpmChange = viewModel::setBpm,
        onExecute = viewModel::execute,
        onStop = viewModel::stop,
        onLoadExample = viewModel::loadExample,
        selectedExample = state.selectedExample,
        activeHighlights = activeHighlights,
        error = state.error,
        modifier = modifier,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange
    )
}

/**
 * Layout for the live coding panel content.
 */
@Composable
fun LiveCodePanelLayout(
    code: TextFieldValue,
    onCodeChange: (TextFieldValue) -> Unit,
    onExecuteBlock: () -> Unit,
    onExecuteLine: () -> Unit,
    isPlaying: Boolean,
    currentCycle: Int,
    cyclePosition: Double,
    bpm: Double,
    onBpmChange: (Double) -> Unit,
    onExecute: () -> Unit,
    onStop: () -> Unit,
    onLoadExample: (String) -> Unit,
    selectedExample: String?,
    activeHighlights: List<IntRange> = emptyList(),
    error: String?,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    // Key on activeHighlights to force recomposition when highlights change
    val syntaxHighlighter = remember(activeHighlights) { 
        LiveCodeTransformer().apply { 
            this.activeHighlights = activeHighlights 
        }
    }

    CollapsibleColumnPanel(
        title = "CODE",
        color = OrpheusColors.neonCyan,
        expandedTitle = "Tidal Live Coding",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,  // Closed by default
        expandedWidth = 280.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Compact Controls & Status
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Play/Stop Group
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPlaying) OrpheusColors.synthGreen.copy(alpha = 0.3f)
                                else OrpheusColors.softPurple
                            )
                            .clickable { onExecute() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "▶",
                            color = if (isPlaying) OrpheusColors.synthGreen else Color.White,
                            fontSize = 12.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                OrpheusColors.warmGlow.copy(alpha = 0.2f)
                            )
                            .clickable(enabled = isPlaying) { onStop() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "■",
                            fontSize = 12.sp,
                            color = if (isPlaying) OrpheusColors.warmGlow else OrpheusColors.warmGlow.copy(alpha = 0.4f)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                ExamplesDropdown(
                    selectedExample = selectedExample,
                    onLoadExample = onLoadExample
                )
            }

            // BPM Control
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )

                HorizontalMiniSlider(
                    trackWidth = 110,
                    value = ((bpm - 40) / 200).toFloat().coerceIn(0f, 1f),
                    onValueChange = { frac ->
                        onBpmChange(40 + (frac * 200).toDouble())
                    },
                    color = OrpheusColors.neonCyan
                )
                Text(
                    text = "${bpm.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OrpheusColors.neonCyan,
                    fontSize = 9.sp
                )
                Text(
                    text = "C:$currentCycle",
                    fontSize = 9.sp,
                    color = if (isPlaying) OrpheusColors.neonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                )


            }

            // Code editor
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = Color(0xFF1E1E2E), // Dark editor background
                shape = RoundedCornerShape(8.dp)
            ) {
                BasicTextField(
                    value = code,
                    onValueChange = { if (!isPlaying) onCodeChange(it) },
                    readOnly = isPlaying,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when {
                                    (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Enter -> {
                                        onExecuteBlock()
                                        true
                                    }

                                    event.isShiftPressed && event.key == Key.Enter -> {
                                        onExecuteLine()
                                        true
                                    }

                                    else -> false
                                }
                            } else false
                        },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = OrpheusColors.neonCyan, // Default text color
                        lineHeight = 15.sp
                    ),
                    visualTransformation = syntaxHighlighter,
                    cursorBrush = SolidColor(OrpheusColors.neonCyan),
                    decorationBox = { innerTextField ->
                        Box {
                            if (code.text.isEmpty()) {
                                Text(
                                    text = "# voices:0 1 2 3\n# fast 2 voices:0 1",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.4f
                                        )
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            // Cycle position bar
            if (isPlaying) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = OrpheusColors.softPurple.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = cyclePosition.toFloat())
                            .height(3.dp)
                            .background(
                                color = OrpheusColors.neonCyan,
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }
            }

            // Error display
            if (error != null) {
                Text(
                    text = "⚠ $error",
                    style = MaterialTheme.typography.labelSmall,
                    color = OrpheusColors.warmGlow,
                    maxLines = 2,
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


@Composable
private fun ExamplesDropdown(
    selectedExample: String?,
    modifier: Modifier = Modifier,
    onLoadExample: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.neonCyan.copy(alpha = 0.12f))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = selectedExample?.replaceFirstChar { it.uppercase() } ?: "Examples",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = OrpheusColors.neonCyan
            )
            Text(
                text = "▼",
                fontSize = 8.sp,
                color = OrpheusColors.neonCyan
            )
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            LiveCodeViewModel.EXAMPLES.keys.forEach { name ->
                DropdownMenuItem(
                    text = { 
                        Text(
                            name.replaceFirstChar { it.uppercase() },
                            fontSize = 12.sp,
                            fontWeight = if (name == selectedExample) FontWeight.Bold else FontWeight.Normal
                        ) 
                    },
                    onClick = {
                        onLoadExample(name)
                        expanded = false
                    }
                )
            }
        }
    }
}

// === Previews ===

@Preview
@Composable
private fun LiveCodePanelCollapsedPreview() {
    OrpheusTheme {
        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            color = OrpheusColors.darkVoid
        ) {
            LiveCodePanelLayout(
                code = TextFieldValue(""),
                onCodeChange = {},
                onExecuteBlock = {},
                onExecuteLine = {},
                isPlaying = false,
                currentCycle = 0,
                cyclePosition = 0.0,
                bpm = 120.0,
                onBpmChange = {},
                onExecute = {},
                onStop = {},
                onLoadExample = {},
                selectedExample = null,
                error = null,
                isExpanded = false
            )
        }
    }
}

@Preview
@Composable
private fun LiveCodePanelExpandedPreview() {
    OrpheusTheme {
        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            color = OrpheusColors.darkVoid
        ) {
            LiveCodePanelLayout(
                code = TextFieldValue("d1 $ s \"bd sn\"\nd2 $ gates:4 5 6 7"),
                onCodeChange = {},
                onExecuteBlock = {},
                onExecuteLine = {},
                isPlaying = false,
                currentCycle = 0,
                cyclePosition = 0.0,
                bpm = 128.0,
                onBpmChange = {},
                onExecute = {},
                onStop = {},
                onLoadExample = {},
                selectedExample = null,
                error = null,
                isExpanded = true
            )
        }
    }
}

@Preview
@Composable
private fun LiveCodePanelPlayingPreview() {
    OrpheusTheme {
        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            color = OrpheusColors.darkVoid
        ) {
            LiveCodePanelLayout(
                code = TextFieldValue("d1 $ s \"bd sn\"\n\n# Playing with progress"),
                onCodeChange = {},
                onExecuteBlock = {},
                onExecuteLine = {},
                isPlaying = true,
                currentCycle = 42,
                cyclePosition = 0.65,
                bpm = 140.0,
                onBpmChange = {},
                onExecute = {},
                onStop = {},
                onLoadExample = {},
                selectedExample = null,
                error = null,
                isExpanded = true
            )
        }
    }
}

@Preview
@Composable
private fun LiveCodePanelErrorPreview() {
    OrpheusTheme {
        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            color = OrpheusColors.darkVoid
        ) {
            LiveCodePanelLayout(
                code = TextFieldValue("d1 $ s \"unknown\""),
                onCodeChange = {},
                onExecuteBlock = {},
                onExecuteLine = {},
                isPlaying = false,
                currentCycle = 0,
                cyclePosition = 0.0,
                bpm = 120.0,
                onBpmChange = {},
                onExecute = {},
                onStop = {},
                onLoadExample = {},
                selectedExample = null,
                error = "Unknown sound: unknown",
                isExpanded = true
            )
        }
    }
}
