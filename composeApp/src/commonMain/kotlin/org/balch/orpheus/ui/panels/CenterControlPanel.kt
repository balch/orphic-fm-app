package org.balch.orpheus.ui.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.ui.widgets.CrossModSelector
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

@Composable
fun CenterControlPanel(modifier: Modifier = Modifier) {
    val voiceViewModel: VoiceViewModel = metroViewModel()
    val voiceState by voiceViewModel.uiState.collectAsState()
    val effects = LocalLiquidEffects.current

    CenterControlPanelLayout(
        voiceState = voiceState,
        effects = effects,
        onVibratoChange = voiceViewModel::onVibratoChange,
        onVoiceCouplingChange = voiceViewModel::onVoiceCouplingChange,
        onTotalFeedbackChange = voiceViewModel::onTotalFeedbackChange,
        onFmStructureChange = voiceViewModel::onFmStructureChange,
        modifier = modifier
    )
}

@Composable
private fun CenterControlPanelLayout(
    voiceState: VoiceUiState,
    effects: VisualizationLiquidEffects,
    onFmStructureChange: (Boolean) -> Unit,
    onTotalFeedbackChange: (Float) -> Unit,
    onVibratoChange: (Float) -> Unit,
    onVoiceCouplingChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liquidState = LocalLiquidState.current
    val shape = RoundedCornerShape(8.dp)
    val density = LocalDensity.current

    val textShadow = remember(effects.title.titleElevation, density) {
        val blur = with(density) { effects.title.titleElevation.toPx() }
        if (blur > 0f) {
            Shadow(
                color = Color.Black.copy(alpha = 0.5f),
                offset = Offset(0f, blur / 2),
                blurRadius = blur
            )
        } else {
            Shadow.None
        }
    }

    Column(
        modifier = modifier
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = OrpheusColors.electricBlue,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Spacer(modifier = Modifier.weight(.5f))
        Card(
            modifier = Modifier
                .liquidVizEffects(
                    liquidState = liquidState,
                    scope = effects.title.scope,
                    frostAmount = effects.frostLarge.dp,
                    color = OrpheusColors.softPurple,
                    tintAlpha = 0.2f,
                    shape = shape,
                ),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = effects.title.titleElevation),
            shape = shape,
            border = BorderStroke(effects.title.borderWidth, effects.title.borderColor)
        ) {
            Text(
                text = "ORPHEUS-8",
                fontSize = effects.title.titleSize,
                fontWeight = FontWeight.Bold,
                color = effects.title.titleColor,
                style = TextStyle(shadow = textShadow),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        CrossModSelector(
            isCrossQuad = voiceState.fmStructureCrossQuad,
            onToggle = onFmStructureChange
        )
        RotaryKnob(
            value = voiceState.totalFeedback,
            onValueChange = onTotalFeedbackChange,
            label = "TOTAL FB",
            controlId = ControlIds.TOTAL_FEEDBACK,
            size = 32.dp,
            progressColor = OrpheusColors.neonCyan
        )
        RotaryKnob(
            value = voiceState.vibrato,
            onValueChange = onVibratoChange,
            label = "VIB",
            controlId = ControlIds.VIBRATO,
            size = 32.dp,
            progressColor = OrpheusColors.neonMagenta
        )
        RotaryKnob(
            value = voiceState.voiceCoupling,
            onValueChange = onVoiceCouplingChange,
            label = "COUPLE",
            controlId = ControlIds.VOICE_COUPLING,
            size = 32.dp,
            progressColor = OrpheusColors.warmGlow
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Preview(heightDp = 360)
@Composable
fun CenterControlPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        CenterControlPanelLayout(
            voiceState = VoiceUiState(),
            effects = effects,
            onVibratoChange = {},
            onVoiceCouplingChange = {},
            onTotalFeedbackChange = {},
            onFmStructureChange = {}
        )
    }
}

