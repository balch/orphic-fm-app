package org.balch.orpheus.ui.panels.compact

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.engineLabel

// ═══════════════════════════════════════════════════════════
// Data model
// Keep engine labels/ordinals in sync with engineLabel() in VoiceEnginePickerPopup.kt
// ═══════════════════════════════════════════════════════════

data class EngineGroup(
    val name: String,
    val color: Color,
    val engines: List<EngineEntry>,
)

data class EngineEntry(
    val label: String,
    val ordinal: Int,
    val description: String,
)

// EngineEntry.ordinal carries OrpheusEngineId.id (C++ engine index directly).
val ENGINE_GROUPS = listOf(
    EngineGroup(
        name = "Classic",
        color = OrpheusColors.neonMagenta,
        engines = listOf(
            EngineEntry("OSC", -1, "Analog oscillator"),
            EngineEntry("VA",   8, "Virtual analog"),
            EngineEntry("FM",  10, "FM synthesis"),
            EngineEntry("WSH",  9, "Waveshaper"),
        ),
    ),
    EngineGroup(
        name = "Textural",
        color = OrpheusColors.synthGreen,
        engines = listOf(
            EngineEntry("GRN", 11, "Granular"),
            EngineEntry("NSE", 17, "Noise"),
            EngineEntry("ADD", 12, "Additive"),
            EngineEntry("WTB", 13, "Wavetable"),
        ),
    ),
    EngineGroup(
        name = "Physical",
        color = OrpheusColors.warmGlow,
        engines = listOf(
            EngineEntry("STR", 19, "String model"),
            EngineEntry("MOD", 20, "Modal resonator"),
            EngineEntry("SWM", 16, "Swarm"),
            EngineEntry("SPK", 15, "Speech"),
        ),
    ),
    EngineGroup(
        name = "Harmonic",
        color = OrpheusColors.electricBlue,
        engines = listOf(
            EngineEntry("PAR", 18, "Partials"),
            EngineEntry("CHD", 14, "Chords"),
        ),
    ),
    EngineGroup(
        name = "V2",
        color = OrpheusColors.neonCyan,
        engines = listOf(
            EngineEntry("VCF",  0, "Filter model"),
            EngineEntry("PD",   1, "Phase distortion"),
            EngineEntry("DX",   2, "6-op FM"),
            EngineEntry("DX2",  3, "6-op FM Bank 2"),
            EngineEntry("DX3",  4, "6-op FM Bank 3"),
            EngineEntry("TRN",  5, "Wave Terrain"),
            EngineEntry("ENS",  6, "Ensemble"),
            EngineEntry("NES",  7, "Chiptune"),
        ),
    ),
)

fun engineGroupFor(ordinal: Int): EngineGroup? =
    ENGINE_GROUPS.find { group -> group.engines.any { it.ordinal == ordinal } }

// ═══════════════════════════════════════════════════════════
// Level 1: Engine group grid
// ═══════════════════════════════════════════════════════════

@Composable
private fun EngineGroupCard(
    group: EngineGroup,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgAlpha = if (isHighlighted) 0.25f else 0.10f
    val borderAlpha = if (isHighlighted) 0.70f else 0.30f
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(group.color.copy(alpha = bgAlpha))
            .border(1.dp, group.color.copy(alpha = borderAlpha), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = group.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = group.color,
            maxLines = 1,
        )
        Text(
            text = group.engines.joinToString(" ") { it.label },
            fontSize = 8.sp,
            color = group.color.copy(alpha = 0.70f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 10.sp,
        )
    }
}

@Composable
private fun EngineGroupGrid(
    currentEngine: Int,
    onGroupSelected: (EngineGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topGroups = ENGINE_GROUPS.take(4)
    val v2Group = ENGINE_GROUPS.last()
    val currentGroup = engineGroupFor(currentEngine)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 2×2 top groups
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            topGroups.take(2).forEach { group ->
                EngineGroupCard(
                    group = group,
                    isHighlighted = group == currentGroup,
                    onClick = { onGroupSelected(group) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            topGroups.drop(2).take(2).forEach { group ->
                EngineGroupCard(
                    group = group,
                    isHighlighted = group == currentGroup,
                    onClick = { onGroupSelected(group) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // V2 spanning full width
        EngineGroupCard(
            group = v2Group,
            isHighlighted = v2Group == currentGroup,
            onClick = { onGroupSelected(v2Group) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ═══════════════════════════════════════════════════════════
// Level 2: Individual engine list
// ═══════════════════════════════════════════════════════════

@Composable
private fun EngineCard(
    entry: EngineEntry,
    groupColor: Color,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgAlpha = if (isHighlighted) 0.25f else 0.10f
    val borderAlpha = if (isHighlighted) 0.70f else 0.25f
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(groupColor.copy(alpha = bgAlpha))
            .border(1.dp, groupColor.copy(alpha = borderAlpha), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Circular label badge
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(groupColor.copy(alpha = if (isHighlighted) 0.35f else 0.18f))
                .border(1.dp, groupColor.copy(alpha = if (isHighlighted) 0.80f else 0.40f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = entry.label,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHighlighted) Color.White else groupColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = entry.description,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHighlighted) Color.White else groupColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EngineListGrid(
    group: EngineGroup,
    currentEngine: Int,
    onBack: () -> Unit,
    onEngineSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Back header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "‹",
                fontSize = 16.sp,
                color = group.color,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = group.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = group.color,
            )
        }

        // 2-column engine grid
        val engines = group.engines
        val rows = (engines.size + 1) / 2
        repeat(rows) { rowIdx ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val left = engines.getOrNull(rowIdx * 2)
                val right = engines.getOrNull(rowIdx * 2 + 1)
                if (left != null) {
                    EngineCard(
                        entry = left,
                        groupColor = group.color,
                        isHighlighted = left.ordinal == currentEngine,
                        onClick = { onEngineSelected(left.ordinal) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (right != null) {
                    EngineCard(
                        entry = right,
                        groupColor = group.color,
                        isHighlighted = right.ordinal == currentEngine,
                        onClick = { onEngineSelected(right.ordinal) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Empty filler to keep left card at half width
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Public API: CompactEnginePicker
// ═══════════════════════════════════════════════════════════

/**
 * A compact tap-to-open engine selector for portrait mode.
 *
 * Renders as a 28dp rounded-rect button showing the current engine label.
 * Tapping opens a two-level popup:
 *  - Level 1: 2×2 group grid + V2 full-width row
 *  - Level 2: individual engines in the selected group with a back button
 *
 * Selecting an engine dismisses the popup and calls [onEngineChange].
 * Tapping outside the popup dismisses without changing the engine.
 */
@Composable
fun CompactEnginePicker(
    currentEngine: Int,
    onEngineChange: (Int) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<EngineGroup?>(null) }

    val label = engineLabel(currentEngine)
    val buttonShape = RoundedCornerShape(6.dp)

    Box(modifier = modifier) {
        // Trigger button
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(buttonShape)
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.45f), buttonShape)
                .clickable {
                    selectedGroup = null
                    showPopup = true
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }

        if (showPopup) {
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = {
                    showPopup = false
                    selectedGroup = null
                },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 200.dp, max = 280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D0D1A).copy(alpha = 0.97f))
                        .border(
                            1.dp,
                            color.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(10.dp)
                ) {
                    AnimatedContent(
                        targetState = selectedGroup,
                        transitionSpec = {
                            if (targetState != null) {
                                // Drilling in: slide left (new content comes from right)
                                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                            } else {
                                // Going back: slide right (new content comes from left)
                                slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                            }
                        },
                        label = "EnginePickerLevel",
                    ) { group ->
                        if (group == null) {
                            EngineGroupGrid(
                                currentEngine = currentEngine,
                                onGroupSelected = { selectedGroup = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            EngineListGrid(
                                group = group,
                                currentEngine = currentEngine,
                                onBack = { selectedGroup = null },
                                onEngineSelected = { ordinal ->
                                    onEngineChange(ordinal)
                                    showPopup = false
                                    selectedGroup = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Previews
// ═══════════════════════════════════════════════════════════

@Preview
@Composable
private fun CompactEnginePickerPreview() {
    OrpheusTheme {
        Row(
            modifier = Modifier
                .background(Color(0xFF0a0515))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactEnginePicker(
                currentEngine = 0,
                onEngineChange = {},
                color = OrpheusColors.neonMagenta,
            )
            CompactEnginePicker(
                currentEngine = 10,
                onEngineChange = {},
                color = OrpheusColors.synthGreen,
            )
            CompactEnginePicker(
                currentEngine = 20,
                onEngineChange = {},
                color = OrpheusColors.neonCyan,
            )
        }
    }
}

@Preview
@Composable
private fun EngineGroupGridPreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier
                .background(Color(0xFF0a0515))
                .padding(16.dp)
                .width(260.dp)
        ) {
            EngineGroupGrid(
                currentEngine = 10,
                onGroupSelected = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview
@Composable
private fun EngineListGridPreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier
                .background(Color(0xFF0a0515))
                .padding(16.dp)
                .width(260.dp)
        ) {
            EngineListGrid(
                group = ENGINE_GROUPS[0],
                currentEngine = 5,
                onBack = {},
                onEngineSelected = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
