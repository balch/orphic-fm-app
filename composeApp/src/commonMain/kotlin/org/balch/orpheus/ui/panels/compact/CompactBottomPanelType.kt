package org.balch.orpheus.ui.panels.compact

import androidx.compose.ui.graphics.Color
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Enum representing available bottom panels for compact portrait mode.
 */
enum class CompactBottomPanelType(val displayName: String, val color: Color) {
    PADS("Pads", OrpheusColors.neonMagenta),
    AI("AI", OrpheusColors.neonCyan),
    STRINGS("Strings", OrpheusColors.warmGlow)
}
