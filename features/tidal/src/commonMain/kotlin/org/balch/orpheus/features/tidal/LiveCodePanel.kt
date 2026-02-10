package org.balch.orpheus.features.tidal

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.tidal.TidalScheduler
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.HorizontalMiniSlider

/**
 * Extended feature() for LiveCode that includes trigger events for highlighting.
 */
interface LiveCodeFeature : SynthFeature<LiveCodeUiState, LiveCodePanelActions> {
    val triggers: Flow<TidalScheduler.TriggerEvent>
}

/**
 * Collapsible live coding panel for the header row.
 * 
 * When collapsed: Shows "CODE" title vertically
 * When expanded: Shows "Tidal Live Coding" header with code editor
 */
@Composable
fun LiveCodePanel(
    feature: LiveCodeFeature = LiveCodeViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions
    
    // Track active highlight ranges for token highlighting (Map of unique ID to range)
    var activeHighlightMap by remember { mutableStateOf(mapOf<Long, IntRange>()) }
    var highlightIdCounter by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    
    // Derive active highlights from the map for the transformer
    val activeHighlights = activeHighlightMap.values.toList()
    
    // Subscribe to trigger events for token highlighting
    LaunchedEffect(Unit) {
        feature.triggers.collect { triggerEvent ->
            // Create unique IDs for each new highlight
            val newHighlights = triggerEvent.locations.associate { loc ->
                val id = highlightIdCounter++
                id to (loc.start until loc.end)
            }
            activeHighlightMap = activeHighlightMap + newHighlights
            
            // Launch coroutine to clear these specific highlights after duration
            val idsToRemove = newHighlights.keys
            scope.launch {
                delay(triggerEvent.durationMs)
                activeHighlightMap = activeHighlightMap - idsToRemove
            }
        }
    }
    
    // Key on activeHighlights to force recomposition when highlights change
    val syntaxHighlighter = remember(activeHighlights) {
        LiveCodeTransformer().apply {
            this.activeHighlights = activeHighlights
        }
    }

    CollapsibleColumnPanel(
        title = "CODE",
        color = OrpheusColors.neonCyan,
        expandedTitle = "Tidal Live",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,  // Closed by default
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Box {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {

                val effects = LocalLiquidEffects.current
                val liquidState = LocalLiquidState.current
                // Header Row: Compact Controls & Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Play/Pause Toggle Button
                    Row(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (uiState.isPlaying) OrpheusColors.synthGreen.copy(alpha = 0.3f)
                                else OrpheusColors.softPurple.copy(alpha = 0.6f)
                            )
                            .clickable {
                                if (uiState.isPlaying) actions.stop() else actions.execute()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (uiState.isPlaying) "▐▐" else "▶",
                            color = if (uiState.isPlaying) OrpheusColors.synthGreen else Color.White,
                            fontSize = 10.sp
                        )
                        Text(
                            text = if (uiState.isPlaying) "Stop" else "Play",
                            color = if (uiState.isPlaying) OrpheusColors.synthGreen else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "C:${uiState.currentCycle}",
                        fontSize = 9.sp,
                        color = if (uiState.isPlaying) OrpheusColors.neonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Active slots indicator
                    if (uiState.activeSlots.isNotEmpty()) {
                        Text(
                            text = uiState.activeSlots.sorted().joinToString(" "),
                            fontSize = 8.sp,
                            color = OrpheusColors.synthGreen.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    ExamplesDropdown(
                        modifier = Modifier.padding(end = 8.dp),
                        selectedExample = uiState.selectedExample,
                        onLoadExample = actions.loadExample
                    )
                }

                // BPM and Volume Controls Row
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // BPM Control
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "BPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontSize = 9.sp
                        )

                        HorizontalMiniSlider(
                            trackWidth = 80,
                            value = ((uiState.bpm - 40) / 200).toFloat().coerceIn(0f, 1f),
                            onValueChange = { frac ->
                                actions.setBpm(40 + (frac * 200).toDouble())
                            },
                            color = OrpheusColors.neonCyan
                        )
                        Text(
                            text = "${uiState.bpm.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = OrpheusColors.neonCyan,
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                    }

                    // REPL Volume Control
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "VOL",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontSize = 9.sp
                        )

                        HorizontalMiniSlider(
                            trackWidth = 60,
                            value = (uiState.replVolume / 1.5f).coerceIn(0f, 1f),  // 0-150% range
                            onValueChange = { frac ->
                                actions.setReplVolume(frac * 1.5f)  // 0.0 to 1.5
                            },
                            color = OrpheusColors.warmGlow
                        )
                        Text(
                            text = "${(uiState.replVolume * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = OrpheusColors.warmGlow,
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Keyboard shortcut hints
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "\u2318\u23ce Run block  \u21e7\u23ce Run line  \u2318\u232b Delete line",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }

                // Code editor with AI loading overlay
                Card(
                    modifier = Modifier.fillMaxSize()
                        .padding(4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, OrpheusColors.neonCyan.copy(alpha = 0.12f))
                ) {
                    BasicTextField(
                        value = uiState.code,
                        onValueChange = { if (!uiState.isAiGenerating) actions.setCode(it) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), // Set action to Send
                        keyboardActions = KeyboardActions(
                            onSend = { actions.executeBlock() }
                        ),
                        readOnly = uiState.isAiGenerating, // Allow editing while playing for true live coding
                        modifier = Modifier
                            .fillMaxSize()
                            .liquidVizEffects(
                                liquidState = liquidState,
                                scope = effects.bottom,
                                frostAmount = effects.frostLarge.dp,
                                color = OrpheusColors.deepSpaceBlue,
                                tintAlpha = .6f,
                            )
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when {
                                        (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Enter -> {
                                            actions.executeBlock()
                                            true
                                        }

                                        event.isShiftPressed && event.key == Key.Enter -> {
                                            actions.executeLine()
                                            true
                                        }

                                        (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Backspace -> {
                                            actions.deleteLine()
                                            actions.executeBlock()
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
                                if (uiState.code.text.isEmpty() && !uiState.isAiGenerating) {
                                    Text(
                                        text = "d1 $ note \"c3 e3 g3\"\nd2 $ s \"bd sn hh\"",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = OrpheusColors.neonCyan.copy(alpha = 0.25f)
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }

            // AI Loading Overlay
            if (uiState.isAiGenerating) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OrpheusColors.tidalBackground.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = OrpheusColors.warmGlow,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "AI generating...",
                            style = MaterialTheme.typography.bodySmall,
                            color = OrpheusColors.warmGlow,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Error display (inside the main panel)
            if (uiState.error != null) {
                Text(
                    text = "⚠ ${uiState.error}",
                    style = MaterialTheme.typography.labelLarge,
                    color = OrpheusColors.warmGlow,
                    maxLines = 2,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
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
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(OrpheusColors.panelSurface)
        ) {
            LiveCodeViewModel.EXAMPLES.keys.forEach { name ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name.replaceFirstChar { it.uppercase() },
                            fontSize = 12.sp,
                            fontWeight = if (name == selectedExample) FontWeight.Bold else FontWeight.Normal,
                            color = Color.White
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
@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun LiveCodePanelExpandedPreview() {
    OrpheusTheme {
        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            color = OrpheusColors.darkVoid
        ) {
            LiveCodePanel(
                feature = LiveCodeViewModel.previewFeature(
                    LiveCodeUiState(
                        code = TextFieldValue("d1 $ s \"bd sn\"\nd2 $ gates:4 5 6 7"),
                    bpm = 128.0
                )
                )
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun LiveCodePanelPlayingPreview() {
    OrpheusTheme {
        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            color = OrpheusColors.darkVoid
        ) {
            LiveCodePanel(
                feature = LiveCodeViewModel.previewFeature(
                    LiveCodeUiState(
                        code = TextFieldValue("d1 $ s \"bd sn\"\n\n# Playing with progress"),
                        isPlaying = true,
                        currentCycle = 42,
                        cyclePosition = 0.65,
                        bpm = 140.0,
                    )
                )
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun LiveCodePanelErrorPreview() {
    OrpheusTheme {
        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            color = OrpheusColors.darkVoid
        ) {
            LiveCodePanel(
                feature = LiveCodeViewModel.previewFeature(
                    LiveCodeUiState(
                        error = "Unknown sound: unknown",
                        isAiGenerating = true,
                        isPlaying = true
                    )
                )
            )
        }
    }
}
