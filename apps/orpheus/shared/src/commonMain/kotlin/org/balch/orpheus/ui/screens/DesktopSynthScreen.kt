package org.balch.orpheus.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.balch.orpheus.core.features.LocalSynthFeatures
import org.balch.orpheus.core.features.feature
import org.balch.orpheus.features.beats.DrumBeatsFeature
import org.balch.orpheus.features.mediapipe.MediaPipeFeature
import org.balch.orpheus.features.midi.MidiFeature
import org.balch.orpheus.features.tweaks.CenterControlSection
import org.balch.orpheus.features.voice.LeftPanelMode
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.features.voice.ui.VoiceGroupSection
import org.balch.orpheus.ui.FactoryPanelSets
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.HeaderFeature
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.AppTitleTreatment

/**
 * The desktop screen - retrieves typed features from the registry.
 */
@Composable
fun DesktopSynthScreen(
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current,
    isDialogActive: Boolean = false,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val registry = LocalSynthFeatures.current
    // CenterControlSection lives in :features:tweaks and takes this as a parameter now, so the
    // screen resolves it here. DrumBeatsViewModel no longer exposes a companion accessor.
    val beatsFeature: DrumBeatsFeature = registry.feature<DrumBeatsFeature>()
    val headerFeature: HeaderFeature = registry.feature<HeaderFeature>()
    val voiceFeature: VoicesFeature = registry.feature<VoicesFeature>()
    val midiFeature: MidiFeature = registry.feature<MidiFeature>()
    val mediaPipeFeature: MediaPipeFeature = registry.feature<MediaPipeFeature>()
    val panels = remember { headerFeature.resolvePanels(FactoryPanelSets.DesktopScreen) }
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val rightQuad = voiceState.selectedRightQuad
    val leftPanel = voiceState.selectedLeftPanel

    // Request focus for keyboard input handling
    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    val keyActions = registry.keyActions

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    SynthKeyboardHandler.handleKeyEvent(
                        keyEvent = event,
                        isDialogActive = isDialogActive,
                        keyActions = keyActions,
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Header panel
            HeaderPanel(
                modifier = Modifier.fillMaxWidth()
                    .weight(0.75f),
                headerFeature = headerFeature,
                panels = panels,
                onDialogActiveChange = onDialogActiveChange,
            )

            // Main content area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Voice groups + center controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    when (leftPanel) {
                        LeftPanelMode.VOICES -> VoiceGroupSection(
                            voiceFeature = voiceFeature,
                            midiFeature = midiFeature,
                            quadLabel = "1-4",
                            quadColor = OrpheusColors.neonMagenta,
                            voiceStartIndex = 0,
                            showQuadToggle = true,
                            onToggle = { voiceFeature.actions.toggleLeftPanel() },
                            modifier = Modifier.weight(1f),
                        )
                        LeftPanelMode.BENDER_STRINGS -> {
                            val externalBends by mediaPipeFeature.stringBends.collectAsState()
                            DesktopBenderStringsSection(
                                voiceState = voiceState,
                                actions = voiceFeature.actions,
                                externalStringBends = externalBends,
                                onToggle = { voiceFeature.actions.toggleLeftPanel() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    CenterControlSection(
                        voiceFeature = voiceFeature,
                        beatsFeature = beatsFeature,
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.4f)
                    )

                    VoiceGroupSection(
                        voiceFeature = voiceFeature,
                        midiFeature = midiFeature,
                        quadLabel = if (rightQuad == 1) "5-8" else "9-12",
                        quadColor = if (rightQuad == 1) OrpheusColors.synthGreen else OrpheusColors.neonCyan,
                        voiceStartIndex = if (rightQuad == 1) 4 else 8,
                        showQuadToggle = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                AppTitleTreatment(
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .align(Alignment.TopCenter),
                    effects = effects,
                )
            }
        }
    }
}
