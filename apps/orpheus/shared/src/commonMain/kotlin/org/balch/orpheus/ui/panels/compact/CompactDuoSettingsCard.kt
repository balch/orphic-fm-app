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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineParam
import org.balch.orpheus.core.audio.EngineSlotKind
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.voice.ui.DuoKnobLabel
import org.balch.orpheus.features.voice.ui.duoKnobLabel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.KnobValueFormatter
import org.balch.orpheus.ui.widgets.ModFaderSelector
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.engine.EnginePicker
import org.balch.orpheus.ui.widgets.engine.rememberEnginePickerController
import kotlin.math.roundToInt

/**
 * A self-contained settings card for one duo with engine picker, mod fader, and knobs.
 * All contents show/hide together via [showModRow] using AnimatedVisibility.
 * [engine] is the C++ engine id. On DX the harmonics knob locks and opens the picker on DX Patches.
 */
@Composable
fun CompactDuoSettingsCard(
    duoIndex: Int,
    color: Color,
    engine: Int,
    hasNativeEngine: Boolean,
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
    val engineId = OrpheusEngineId.fromId(engine) ?: OrpheusEngineId.OSC
    val controller = rememberEnginePickerController()
    val choices = remember(hasNativeEngine) { EngineCatalog.choices(EngineSlotKind.DUO, hasNativeEngine) }

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
                val current = EngineCatalog.choiceFor(engineId, harmonics)
                EnginePicker(
                    current = current,
                    choices = choices,
                    onPick = { choice ->
                        onEngineChange(choice.engine.id)
                        if (choice is EngineChoice.Patch) onHarmonicsChange(choice.patch.harmonics)
                    },
                    color = color,
                    compact = true,
                    pillHeight = 24.dp,
                    controller = controller,
                    // The code only: the locked harmonics knob names the patch.
                    pillLabel = (current as? EngineChoice.Patch)?.patch?.engine?.name,
                    modifier = Modifier.widthIn(max = 96.dp),
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
                CompactDuoKnob(morph, onMorphChange, duoKnobLabel(engineId, EngineParam.MORPH, harmonics), color)
                CompactDuoKnob(
                    value = harmonics,
                    onValueChange = onHarmonicsChange,
                    label = duoKnobLabel(engineId, EngineParam.HARMONICS, harmonics),
                    color = color,
                    onLockedClick = { controller.open(EngineCategory.DX_PATCHES) },
                    valueFormatter = { v -> EngineCatalog.patchAt(engineId, v)?.let { "#${it.index + 1}" } ?: "" },
                )
                CompactDuoKnob(sharpness, onSharpnessChange, duoKnobLabel(engineId, EngineParam.TIMBRE, harmonics), color)
            }
        }
    }
}

/** An 18dp duo knob naming its job; inactive dims, locked opens [onLockedClick] and shows [valueFormatter]. */
@Composable
private fun CompactDuoKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: DuoKnobLabel,
    color: Color,
    onLockedClick: (() -> Unit)? = null,
    valueFormatter: KnobValueFormatter? = null,
) {
    RotaryKnob(
        value = value,
        onValueChange = onValueChange,
        label = label.short,
        caption = label.glyph,
        longLabel = label.long,
        locked = label.locked,
        onLockedClick = onLockedClick,
        valueFormatter = if (label.locked && valueFormatter != null) valueFormatter else DefaultValueFormatter,
        size = 18.dp,
        progressColor = color,
        labelColor = if (label.active) color else color.copy(alpha = 0.4f),
        modifier = Modifier.widthIn(max = 64.dp).alpha(if (label.active) 1f else 0.5f),
    )
}

private val DefaultValueFormatter: KnobValueFormatter = { v -> ((v * 100).roundToInt() / 100.0).toString() }

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
            engine = OrpheusEngineId.VA.id,
            hasNativeEngine = true,
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

@Preview(widthDp = 180, heightDp = 120)
@Composable
private fun CompactDuoSettingsCardDxPreview() {
    OrpheusTheme {
        CompactDuoSettingsCard(
            duoIndex = 2,
            color = OrpheusColors.warmGlow,
            engine = OrpheusEngineId.DX2.id,
            hasNativeEngine = true,
            modSource = ModSource.OFF,
            modSourceLevel = 0.0f,
            morph = 0.5f,
            harmonics = EngineCatalog.patches.first { it.engine == OrpheusEngineId.DX2 }.harmonics,
            sharpness = 0.5f,
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
            engine = OrpheusEngineId.TRN.id,
            hasNativeEngine = true,
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
