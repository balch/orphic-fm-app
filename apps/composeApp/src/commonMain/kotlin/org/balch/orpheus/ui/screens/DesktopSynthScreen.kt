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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.feature
import org.balch.orpheus.core.features.LocalSynthFeatures
import org.balch.orpheus.core.input.KeyBinding
import org.balch.orpheus.features.midi.MidiFeature
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.tweaks.CenterControlSection
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.features.voice.ui.VoiceGroupSection
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.HeaderFeature
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.panels.HeaderViewModel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.AppTitleTreatment

/**
 * The desktop screen - retrieves typed features from the registry and
 * delegates to [DesktopSynthLayout] for the actual UI.
 */
@Composable
fun DesktopSynthScreen(
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current,
    isDialogActive: Boolean = false,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val registry = LocalSynthFeatures.current
    DesktopSynthLayout(
        headerFeature = registry.feature<HeaderViewModel, HeaderFeature>(),
        voiceFeature = registry.feature<VoiceViewModel, VoicesFeature>(),
        midiFeature = registry.feature<MidiViewModel, MidiFeature>(),
        keyActions = registry.keyActions,
        effects = effects,
        isDialogActive = isDialogActive,
        onDialogActiveChange = onDialogActiveChange,
        focusRequester = focusRequester,
    )
}

/**
 * Previewable desktop layout — accepts all features as explicit parameters.
 */
@Composable
fun DesktopSynthLayout(
    headerFeature: HeaderFeature,
    voiceFeature: VoicesFeature,
    midiFeature: MidiFeature,
    keyActions: Map<Key, List<KeyBinding>>,
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current,
    isDialogActive: Boolean = false,
    onDialogActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val panels: List<FeaturePanel> = headerFeature.visiblePanels

    // Request focus for keyboard input handling
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

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
                    VoiceGroupSection(
                        voiceFeature = voiceFeature,
                        midiFeature = midiFeature,
                        quadLabel = "1-4",
                        quadColor = OrpheusColors.neonMagenta,
                        voiceStartIndex = 0,
                        modifier = Modifier.weight(1f),
                    )

                    CenterControlSection(
                        voiceFeature = voiceFeature,
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.4f)
                    )

                    VoiceGroupSection(
                        voiceFeature = voiceFeature,
                        midiFeature = midiFeature,
                        quadLabel = "5-8",
                        quadColor = OrpheusColors.synthGreen,
                        voiceStartIndex = 4,
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
