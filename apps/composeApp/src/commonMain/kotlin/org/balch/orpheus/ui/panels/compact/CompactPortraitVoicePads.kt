package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.BenderFaderWidget
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob

/**
 * Compact Voice Pads panel for bottom panel navigation in portrait mode.
 *
 * Layout:
 * - 8 voices arranged in two columns stacked from bottom:
 *   - Left column: 1, 2, 3, 4 (top to bottom)
 *   - Right column: 8, 7, 6, 5 (top to bottom)
 * - Each voice has a pulse button and small tune knob
 * - Quad pitch/hold controls above each column
 */
@Composable
fun CompactPortraitVoicePads(
    voiceState: VoiceUiState,
    actions: VoicePanelActions,
    modifier: Modifier = Modifier,
    liquidState: LiquidState? = LocalLiquidState.current,
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current
) {
    val shape = RoundedCornerShape(12.dp)

    val baseModifier = modifier.fillMaxSize()

    val panelModifier = if (liquidState != null) {
        baseModifier
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = OrpheusColors.softPurple,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(8.dp)
    } else {
        baseModifier.padding(8.dp)
    }

    Row(
        modifier = panelModifier,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Left column: Voices 1-4 with Quad 0 controls
        VoiceColumn(
            isReversed = true,
            voiceIndices = listOf(3, 2, 1, 0), // Voices 1, 2, 3, 4
            quadIndex = 0,
            voiceState = voiceState,
            actions = actions,
            colors = listOf(
                OrpheusColors.electricBlue,
                OrpheusColors.electricBlue,
                OrpheusColors.neonMagenta,
                OrpheusColors.neonMagenta,
            ),
            modifier = Modifier.weight(1f)
        )

        // Center: Bender slider between columns
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BenderFaderWidget(
                value = voiceState.bendPosition, // Reflects actual bend position (including AI control)
                onValueChange = { actions.setBend(it) },
                onRelease = { actions.releaseBend() },
                trackHeight = 120, // Use defaults for other params (wider thumb, narrower track)
                accentColor = OrpheusColors.softPurple
            )
        }

        // Right column: Voices 8, 7, 6, 5 with Quad 1 controls
        VoiceColumn(
            voiceIndices = listOf(4, 5, 6, 7), // Voices 8, 7, 6, 5 (reversed)
            quadIndex = 1,
            voiceState = voiceState,
            actions = actions,
            colors = listOf(
                OrpheusColors.warmGlow,
                OrpheusColors.warmGlow,
                OrpheusColors.synthGreen,
                OrpheusColors.synthGreen,
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * A column of 4 voice pads with quad controls at top.
 */
@Composable
private fun VoiceColumn(
    voiceIndices: List<Int>,
    quadIndex: Int,
    voiceState: VoiceUiState,
    actions: VoicePanelActions,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    isReversed: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Quad controls at top
        QuadControls(
            quadIndex = quadIndex,
            pitch = voiceState.quadGroupPitches.getOrElse(quadIndex) { 0.5f },
            hold = voiceState.quadGroupHolds.getOrElse(quadIndex) { 0f },
            onPitchChange = { actions.setQuadPitch(quadIndex, it) },
            onHoldChange = { actions.setQuadHold(quadIndex, it) },
            color = colors.first()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Voice pads stacked vertically
        Column(
            modifier = Modifier
                .weight(1f)
                .align(
                    if (isReversed) Alignment.Start
                    else Alignment.End
                )
                .padding(horizontal = 8.dp)
            ,
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            voiceIndices.forEachIndexed { index, voiceIndex ->
                val voiceNumber = voiceIndex + 1
                val voiceInfo = voiceState.voiceStates.getOrNull(voiceIndex)
                MiniVoicePad(
                    isReversed = isReversed,
                    voiceNumber = voiceNumber,
                    tune = voiceInfo?.tune ?: 0.5f,
                    isActive = voiceInfo?.pulse == true,
                    onTuneChange = { actions.setVoiceTune(voiceIndex, it) },
                    onPulseStart = { actions.pulseStart(voiceIndex) },
                    onPulseEnd = { actions.pulseEnd(voiceIndex) },
                    color = colors.getOrElse(index) { OrpheusColors.neonCyan },
                    controlId = VoiceSymbol.tune(voiceIndex).controlId.key
                )
            }
        }
    }
}

/**
 * Quad pitch and hold controls.
 */
@Composable
private fun QuadControls(
    quadIndex: Int,
    pitch: Float,
    hold: Float,
    onPitchChange: (Float) -> Unit,
    onHoldChange: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RotaryKnob(
            value = pitch,
            onValueChange = onPitchChange,
            label = "PITCH",
            controlId = VoiceSymbol.quadPitch(quadIndex).controlId.key,
            size = 36.dp,
            progressColor = color
        )
        RotaryKnob(
            value = hold,
            onValueChange = onHoldChange,
            label = "HOLD",
            controlId = VoiceSymbol.quadHold(quadIndex).controlId.key,
            size = 36.dp,
            progressColor = OrpheusColors.warmGlow
        )
    }
}

/**
 * Mini voice pad with pulse button and tune knob.
 */
@Composable
private fun MiniVoicePad(
    voiceNumber: Int,
    tune: Float,
    isActive: Boolean,
    onTuneChange: (Float) -> Unit,
    onPulseStart: () -> Unit,
    onPulseEnd: () -> Unit,
    color: Color,
    controlId: String? = null,
    modifier: Modifier = Modifier,
    isReversed: Boolean,
) {
    LazyRow(
        reverseLayout = isReversed,
        modifier = modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            // Tune knob
            RotaryKnob(
                value = tune,
                onValueChange = onTuneChange,
                size = 28.dp,
                progressColor = color,
                controlId = controlId
            )
        }

        item {
            // Pulse button
            PulseButton(
                size = 38.dp,
                label = "",
                isActive = isActive,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                activeColor = color
            )
        }

        // Voice number label
        item {
            Text(
                text = "$voiceNumber",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 300)
@Composable
private fun CompactPortraitVoicePadsPreview() {
    OrpheusTheme {
        CompactPortraitVoicePads(
            voiceState = VoiceUiState(
                voiceStates = List(8) { index -> VoiceState(index = index) },
                quadGroupPitches = listOf(0.5f, 0.5f),
                quadGroupHolds = listOf(0f, 0f)
            ),
            actions = VoicePanelActions.EMPTY
        )
    }
}
