package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import org.balch.orpheus.core.audio.ModSource
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

private val MinHeightForDuoSettings = 350.dp

/**
 * Compact Voice Pads panel for bottom panel navigation in portrait mode.
 *
 * Layout:
 * - 8 voices arranged in two columns stacked from bottom:
 *   - Left column: 1, 2, 3, 4 (top to bottom)
 *   - Right column: 8, 7, 6, 5 (top to bottom)
 * - Each voice has a pulse button and small tune knob
 * - Quad pitch/hold controls above each column
 * - Per-duo settings cards shown based on topSectionHeight
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

    BoxWithConstraints(modifier = panelModifier) {
        val showDuoSettings = maxHeight >= MinHeightForDuoSettings

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Left column: Voices 1-4 with Duo 0/1 settings and Quad 0 controls
            DuoVoiceColumn(
                isReversed = true,
                duoConfigs = listOf(
                    DuoColumnConfig(duoIndex = 1, voiceIndices = listOf(3, 2), color = OrpheusColors.electricBlue),
                    DuoColumnConfig(duoIndex = 0, voiceIndices = listOf(1, 0), color = OrpheusColors.neonMagenta),
                ),
                quadIndex = 0,
                voiceState = voiceState,
                actions = actions,
                showDuoSettings = showDuoSettings,
                modifier = Modifier.weight(1f),
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

            // Right column: Voices 5-8 with Duo 2/3 settings and Quad 1 controls
            DuoVoiceColumn(
                duoConfigs = listOf(
                    DuoColumnConfig(duoIndex = 2, voiceIndices = listOf(4, 5), color = OrpheusColors.warmGlow),
                    DuoColumnConfig(duoIndex = 3, voiceIndices = listOf(6, 7), color = OrpheusColors.synthGreen),
                ),
                quadIndex = 1,
                voiceState = voiceState,
                actions = actions,
                showDuoSettings = showDuoSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private data class DuoColumnConfig(
    val duoIndex: Int,
    val voiceIndices: List<Int>,
    val color: Color,
)

/**
 * A column containing quad controls at top, then for each duo: a settings card followed by its voice pads.
 */
@Composable
private fun DuoVoiceColumn(
    duoConfigs: List<DuoColumnConfig>,
    quadIndex: Int,
    voiceState: VoiceUiState,
    actions: VoicePanelActions,
    showDuoSettings: Boolean,
    modifier: Modifier = Modifier,
    isReversed: Boolean = false,
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
            color = duoConfigs.first().color,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Voice pads with per-duo settings cards
        Column(
            modifier = Modifier
                .weight(1f)
                .align(
                    if (isReversed) Alignment.Start
                    else Alignment.End
                )
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            duoConfigs.forEach { config ->
                // Settings card — uses intrinsic height, never squished
                CompactDuoSettingsCard(
                    duoIndex = config.duoIndex,
                    color = config.color,
                    engine = voiceState.duoEngines.getOrElse(config.duoIndex) { 0 },
                    modSource = voiceState.duoModSources.getOrElse(config.duoIndex) { ModSource.OFF },
                    modSourceLevel = voiceState.duoModSourceLevels.getOrElse(config.duoIndex) { 0f },
                    morph = voiceState.duoMorphs.getOrElse(config.duoIndex) { 0f },
                    harmonics = voiceState.duoHarmonics.getOrElse(config.duoIndex) { 0.5f },
                    sharpness = voiceState.duoSharpness.getOrElse(config.duoIndex) { 0f },
                    onEngineChange = { actions.setDuoEngine(config.duoIndex, it) },
                    onModSourceChange = { actions.setDuoModSource(config.duoIndex, it) },
                    onModSourceLevelChange = { actions.setDuoModSourceLevel(config.duoIndex, it) },
                    onMorphChange = { actions.setDuoMorph(config.duoIndex, it) },
                    onHarmonicsChange = { actions.setDuoHarmonics(config.duoIndex, it) },
                    onSharpnessChange = { actions.setDuoSharpness(config.duoIndex, it) },
                    showModRow = showDuoSettings,
                )

                // Voice pads — share remaining space equally
                config.voiceIndices.forEach { voiceIndex ->
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
                        color = config.color,
                        controlId = VoiceSymbol.tune(voiceIndex).controlId.key,
                    )
                }
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

@Preview(widthDp = 360, heightDp = 400)
@Composable
private fun CompactPortraitVoicePadsExpandedPreview() {
    OrpheusTheme {
        CompactPortraitVoicePads(
            voiceState = VoiceUiState(
                voiceStates = List(8) { index -> VoiceState(index = index) },
                quadGroupPitches = listOf(0.5f, 0.5f),
                quadGroupHolds = listOf(0f, 0f)
            ),
            actions = VoicePanelActions.EMPTY,
        )
    }
}

@Preview(widthDp = 360, heightDp = 250)
@Composable
private fun CompactPortraitVoicePadsCollapsedPreview() {
    OrpheusTheme {
        CompactPortraitVoicePads(
            voiceState = VoiceUiState(
                voiceStates = List(8) { index -> VoiceState(index = index) },
                quadGroupPitches = listOf(0.5f, 0.5f),
                quadGroupHolds = listOf(0f, 0f)
            ),
            actions = VoicePanelActions.EMPTY,
        )
    }
}
