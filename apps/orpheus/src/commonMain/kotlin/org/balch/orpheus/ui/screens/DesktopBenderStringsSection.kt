package org.balch.orpheus.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.balch.orpheus.features.voice.LeftPanelMode
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.panels.compact.BenderStringsCanvas
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.BenderFaderWidget

/**
 * Desktop bender + strings section for the left panel.
 * Shows BenderFaderWidget on the left edge and BenderStringsCanvas filling the rest.
 */
@Composable
fun DesktopBenderStringsSection(
    voiceState: VoiceUiState,
    actions: VoicePanelActions,
    externalStringBends: List<Float> = listOf(0f, 0f, 0f, 0f),
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    val shape = RoundedCornerShape(10.dp)
    val color = OrpheusColors.neonMagenta

    val coroutineScope = rememberCoroutineScope()
    val slideBarAnim = remember { Animatable(0f) }

    // Persistent string positions
    val stringCentersState = rememberSaveable(saver = Saver(
        save = { it.value },
        restore = { mutableStateOf(it) }
    )) {
        mutableStateOf(listOf(0.20f, 0.40f, 0.60f, 0.80f))
    }
    var stringCenters by stringCentersState

    Column(
        modifier = modifier
            .fillMaxHeight()
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = color,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, color.copy(alpha = 0.3f), shape)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Header with toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text("BEND", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
            IconButton(
                onClick = onToggle,
                modifier = Modifier.padding(start = 4.dp).size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Toggle to voices",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Bender fader + Strings canvas side by side
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Bender fader — equal weight with strings
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BenderFaderWidget(
                    value = voiceState.bendPosition,
                    onValueChange = { actions.setBend(it) },
                    onRelease = { actions.releaseBend() },
                    trackHeight = 220,
                    trackWidth = 16,
                    thumbWidth = 64,
                    thumbHeight = 34,
                    accentColor = OrpheusColors.softPurple,
                )
            }

            // Strings canvas — equal weight with bender
            BenderStringsCanvas(
                colors = listOf(
                    OrpheusColors.neonMagenta,
                    OrpheusColors.electricBlue,
                    OrpheusColors.warmGlow,
                    OrpheusColors.synthGreen
                ),
                voiceStates = voiceState.voiceStates,
                stringCenters = stringCenters,
                externalStringBends = externalStringBends,
                onStringPositionsChanged = { stringCenters = it },
                slideBarPosition = { slideBarAnim.value },
                onStringStart = { stringIndex, bendAmount, voiceMix ->
                    actions.setStringBend(stringIndex, bendAmount, voiceMix)
                },
                onStringBendChange = { stringIndex, bendAmount, voiceMix ->
                    actions.setStringBend(stringIndex, bendAmount, voiceMix)
                },
                onStringBendRelease = { stringIndex ->
                    actions.releaseStringBend(stringIndex)
                },
                onStringTap = { stringIndex, voiceMix ->
                    actions.setStringBend(stringIndex, 0.3f, voiceMix)
                    actions.releaseStringBend(stringIndex)
                },
                onSlideChange = { yPos, xPos ->
                    coroutineScope.launch {
                        slideBarAnim.snapTo(yPos)
                    }
                    actions.setSlideBar(yPos, xPos)
                },
                onSlideRelease = {
                    coroutineScope.launch {
                        slideBarAnim.animateTo(
                            targetValue = 0f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = 0.6f,
                                stiffness = 300f
                            )
                        )
                        actions.setSlideBar(0f, 0.5f)
                        actions.releaseSlideBar()
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp)
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 500)
@Composable
private fun DesktopBenderStringsSectionPreview() {
    org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient {
        DesktopBenderStringsSection(
            voiceState = VoiceUiState(selectedLeftPanel = LeftPanelMode.BENDER_STRINGS),
            actions = VoicePanelActions.EMPTY,
            onToggle = {},
            modifier = Modifier.fillMaxHeight().width(400.dp)
        )
    }
}
