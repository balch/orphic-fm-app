package org.balch.orpheus.ui.panels.compact

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.ModFaderSelector
import org.balch.orpheus.ui.widgets.RotaryKnob

/**
 * A self-contained settings card for one duo with engine picker, mod fader, and knobs.
 * All contents show/hide together via [showModRow] using AnimatedVisibility.
 */
@Composable
fun CompactDuoSettingsCard(
    duoIndex: Int,
    color: Color,
    engine: Int,
    modSource: ModSource,
    modSourceLevel: Float,
    morph: Float,
    harmonics: Float,
    sharpness: Float,
    onEngineChange: (Int) -> Unit,
    onModSourceChange: (ModSource) -> Unit,
    onModSourceLevelChange: (Float) -> Unit,
    onMorphChange: (Float) -> Unit,
    onHarmonicsChange: (Float) -> Unit,
    onSharpnessChange: (Float) -> Unit,
    showModRow: Boolean,
    modifier: Modifier = Modifier,
) {
    val modFaderControlId = VoiceSymbol.duoModSource(duoIndex).controlId.key

    AnimatedVisibility(
        visible = showModRow,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        Column {
            // Row 1: Mod fader + Engine picker
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModFaderSelector(
                    depth = modSourceLevel,
                    onDepthChange = onModSourceLevelChange,
                    activeSource = modSource,
                    onSourceChange = onModSourceChange,
                    color = color,
                    controlId = modFaderControlId,
                )
                CompactEnginePicker(
                    currentEngine = engine,
                    onEngineChange = onEngineChange,
                    color = color,
                )
            }

            // Row 2: Morph / Harmonics / Sharpness knobs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RotaryKnob(
                    value = morph,
                    onValueChange = onMorphChange,
                    label = "\u03C8",
                    size = 18.dp,
                    progressColor = color,
                )
                RotaryKnob(
                    value = harmonics,
                    onValueChange = onHarmonicsChange,
                    label = "\u2261",
                    size = 18.dp,
                    progressColor = color,
                )
                RotaryKnob(
                    value = sharpness,
                    onValueChange = onSharpnessChange,
                    label = "\u266F",
                    size = 18.dp,
                    progressColor = color,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Previews
// ═══════════════════════════════════════════════════════════

@Preview(widthDp = 180, heightDp = 120)
@Composable
private fun CompactDuoSettingsCardExpandedPreview() {
    OrpheusTheme {
        CompactDuoSettingsCard(
            duoIndex = 0,
            color = OrpheusColors.neonMagenta,
            engine = 0,
            modSource = ModSource.LFO,
            modSourceLevel = 0.6f,
            morph = 0.5f,
            harmonics = 0.3f,
            sharpness = 0.7f,
            onEngineChange = {},
            onModSourceChange = {},
            onModSourceLevelChange = {},
            onMorphChange = {},
            onHarmonicsChange = {},
            onSharpnessChange = {},
            showModRow = true,
            modifier = Modifier.background(Color(0xFF0a0515)),
        )
    }
}

@Preview(widthDp = 180, heightDp = 40)
@Composable
private fun CompactDuoSettingsCardCollapsedPreview() {
    OrpheusTheme {
        CompactDuoSettingsCard(
            duoIndex = 1,
            color = OrpheusColors.electricBlue,
            engine = 5,
            modSource = ModSource.OFF,
            modSourceLevel = 0.0f,
            morph = 0.5f,
            harmonics = 0.5f,
            sharpness = 0.5f,
            onEngineChange = {},
            onModSourceChange = {},
            onModSourceLevelChange = {},
            onMorphChange = {},
            onHarmonicsChange = {},
            onSharpnessChange = {},
            showModRow = false,
            modifier = Modifier.background(Color(0xFF0a0515)),
        )
    }
}
