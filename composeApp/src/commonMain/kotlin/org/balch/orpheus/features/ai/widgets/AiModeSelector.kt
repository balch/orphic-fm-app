package org.balch.orpheus.features.ai.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * AI operation modes for the compact portrait layout.
 */
enum class AiMode(val displayName: String, val color: Color) {
    DRONE("Drone", OrpheusColors.synthGreen),
    SOLO("Solo", OrpheusColors.neonMagenta),
    TIDAL("Tidal", OrpheusColors.neonCyan)
}

/**
 * Segmented button control for selecting AI mode.
 * 
 * Features:
 * - Three modes: Drone, Solo, Tidal
 * - Animated color transitions
 * - Glowing effect on selected mode
 * - Compact design for mobile
 */
@Composable
fun AiModeSelector(
    selectedMode: AiMode?,
    onModeSelected: (AiMode?) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 8.dp)
            .clip(shape)
            .background(OrpheusColors.darkVoid.copy(alpha = 0.7f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AiMode.entries.forEach { mode ->
            ModeButton(
                mode = mode,
                isSelected = selectedMode == mode,
                onClick = {
                    // Toggle off if already selected, otherwise select
                    if (selectedMode == mode) {
                        onModeSelected(null)
                    } else {
                        onModeSelected(mode)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeButton(
    mode: AiMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) mode.color.copy(alpha = 0.3f) else Color.Transparent,
        animationSpec = tween(150)
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) mode.color else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(150)
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) mode.color.copy(alpha = 0.8f) else Color.Transparent,
        animationSpec = tween(150)
    )

    val buttonShape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(buttonShape)
            .background(backgroundColor)
            .border(1.dp, borderColor, buttonShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = mode.displayName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            letterSpacing = 0.5.sp
        )
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 60)
@Composable
private fun AiModeSelectorPreview_None() {
    OrpheusTheme {
        Box(modifier = Modifier.background(OrpheusColors.darkVoid)) {
            AiModeSelector(
                selectedMode = null,
                onModeSelected = {}
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 60)
@Composable
private fun AiModeSelectorPreview_Drone() {
    OrpheusTheme {
        Box(modifier = Modifier.background(OrpheusColors.darkVoid)) {
            AiModeSelector(
                selectedMode = AiMode.DRONE,
                onModeSelected = {}
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 60)
@Composable
private fun AiModeSelectorPreview_Solo() {
    OrpheusTheme {
        Box(modifier = Modifier.background(OrpheusColors.darkVoid)) {
            AiModeSelector(
                selectedMode = AiMode.SOLO,
                onModeSelected = {}
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 60)
@Composable
private fun AiModeSelectorPreview_Tidal() {
    OrpheusTheme {
        Box(modifier = Modifier.background(OrpheusColors.darkVoid)) {
            var selected by remember { mutableStateOf<AiMode?>(AiMode.TIDAL) }
            AiModeSelector(
                selectedMode = selected,
                onModeSelected = { selected = it }
            )
        }
    }
}
