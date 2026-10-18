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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import org.balch.orpheus.ui.panels.slots.PanelNavigationLayout
import org.balch.orpheus.ui.panels.slots.PanelSearchOverlay
import org.balch.orpheus.ui.panels.slots.isSearchShortcut
import org.balch.orpheus.ui.panels.slots.rememberPanelNavCatalog
import org.balch.orpheus.ui.panels.slots.rememberPanelNavState
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
    val panels = remember(headerFeature) {
        headerFeature.resolvePanels(FactoryPanelSets.DesktopScreen).associateBy { it.panelId }
    }
    val headerState by headerFeature.stateFlow.collectAsStateWithLifecycle()
    val navCatalog = rememberPanelNavCatalog(panels)
    val nav = rememberPanelNavState(headerState, headerFeature.actions)
    val voiceState by voiceFeature.stateFlow.collectAsStateWithLifecycle()
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
                    val searchOpen = nav.searchRequest != null
                    // Both halves of Cmd/Ctrl+K are eaten so the K gate never fires; once search is
                    // open the keys belong to it.
                    if (!searchOpen && isSearchShortcut(event.key, event.isMetaPressed, event.isCtrlPressed)) {
                        if (event.type == KeyEventType.KeyDown) nav.openSearch(autoFocus = true)
                        return@onPreviewKeyEvent true
                    }
                    SynthKeyboardHandler.handleKeyEvent(
                        keyEvent = event,
                        isDialogActive = isDialogActive || searchOpen,
                        keyActions = keyActions,
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            PanelNavigationLayout(
                panels = panels,
                state = headerState,
                actions = headerFeature.actions,
                nav = nav,
                onDialogActiveChange = onDialogActiveChange,
                modifier = Modifier.fillMaxWidth()
                    .weight(0.75f),
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
                            val externalBends by mediaPipeFeature.stringBends.collectAsStateWithLifecycle()
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

        // A sibling of the focusable Column, so keys typed into search never reach the synth handler.
        nav.searchRequest?.let { request ->
            val closeSearch: () -> Unit = {
                nav.closeSearch()
                // The overlay held focus; without this the synth keys go dead after a search.
                focusRequester.requestFocus()
            }
            val currentOnDialogActiveChange by rememberUpdatedState(onDialogActiveChange)
            // Raised for exactly as long as the overlay is composed, so synth keys stay quiet.
            DisposableEffect(Unit) {
                currentOnDialogActiveChange(true)
                onDispose { currentOnDialogActiveChange(false) }
            }
            PanelSearchOverlay(
                candidates = navCatalog.candidates,
                colors = navCatalog.colors,
                autoFocus = request.autoFocus,
                onPick = { id ->
                    nav.open(id)
                    closeSearch()
                },
                onDismiss = closeSearch,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
