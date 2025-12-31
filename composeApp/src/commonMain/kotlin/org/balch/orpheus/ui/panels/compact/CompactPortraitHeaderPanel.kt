package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compact header panel for portrait mode mobile layout.
 * 
 * Features:
 * - Title with liquid glass effect
 * - Preset and visualization dropdowns  
 * - Master volume knob with peak LED
 * - Optimized for narrow portrait width
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactPortraitHeaderPanel(
    selectedPresetName: String,
    presets: List<DronePreset>,
    presetDropdownExpanded: Boolean,
    onPresetDropdownExpandedChange: (Boolean) -> Unit,
    onPresetSelect: (DronePreset) -> Unit,
    peakLevel: Float,
    liquidState: LiquidState?,
    effects: VisualizationLiquidEffects,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .then(
                if (liquidState != null) {
                    Modifier.liquidVizEffects(
                        liquidState = liquidState,
                        scope = effects.top,
                        frostAmount = 6.dp,
                        color = OrpheusColors.darkVoid,
                        shape = shape
                    )
                } else Modifier.background(OrpheusColors.darkVoid.copy(alpha = 0.9f))
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Title
        Text(
            text = "ORPHEUS-8",
            style = MaterialTheme.typography.titleMedium,
            color = OrpheusColors.neonCyan,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        // Center: Preset dropdown
        ExposedDropdownMenuBox(
            expanded = presetDropdownExpanded,
            onExpandedChange = onPresetDropdownExpandedChange
        ) {
            Box(
                modifier = Modifier
                    .menuAnchor()
                    .width(120.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .clickable { onPresetDropdownExpandedChange(!presetDropdownExpanded) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedPresetName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetDropdownExpanded)
                }
            }
            ExposedDropdownMenu(
                expanded = presetDropdownExpanded,
                onDismissRequest = { onPresetDropdownExpandedChange(false) },
                modifier = Modifier.background(OrpheusColors.softPurple)
            ) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onPresetSelect(preset)
                            onPresetDropdownExpandedChange(false)
                        }
                    )
                }
            }
        }

        // Right: Peak LED
        PeakLed(level = peakLevel)
    }
}

@Composable
private fun PeakLed(level: Float) {
    val size = 10.dp
    val active = level > 0.01f
    val clipping = level > 0.95f
    
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                when {
                    clipping -> Color.Red
                    active -> OrpheusColors.neonCyan.copy(alpha = 0.5f + (level * 0.5f))
                    else -> Color(0xFF1A1A2A)
                }
            )
            .border(
                1.dp, 
                if (clipping) Color.Red else OrpheusColors.neonCyan.copy(alpha = 0.3f),
                androidx.compose.foundation.shape.CircleShape
            )
    )
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 70)
@Composable
private fun CompactPortraitHeaderPanelPreview() {
    CompactPortraitHeaderPanel(
        selectedPresetName = "Factory 1",
        presets = emptyList(),
        presetDropdownExpanded = false,
        onPresetDropdownExpandedChange = {},
        onPresetSelect = {},
        peakLevel = 0.5f,
        liquidState = null,
        effects = VisualizationLiquidEffects()
    )
}
