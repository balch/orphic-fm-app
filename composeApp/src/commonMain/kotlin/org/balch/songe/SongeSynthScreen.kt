package org.balch.songe

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.songe.core.midi.LearnTarget
import org.balch.songe.features.delay.ModDelayPanel
import org.balch.songe.features.distortion.DistortionPanel
import org.balch.songe.features.lfo.HyperLfoPanel
import org.balch.songe.features.midi.MidiPanel
import org.balch.songe.features.midi.MidiProps
import org.balch.songe.features.midi.MidiViewModel
import org.balch.songe.features.presets.PresetProps
import org.balch.songe.features.presets.PresetViewModel
import org.balch.songe.features.presets.PresetsPanel
import org.balch.songe.features.voice.SynthKeyboardHandler
import org.balch.songe.features.voice.VoiceViewModel
import org.balch.songe.features.voice.ui.VoiceGroupSection
import org.balch.songe.ui.panels.CenterControlPanel
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.widgets.LearnModeProvider
import org.balch.songe.util.Logger
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview(widthDp = 800, heightDp = 600)
@Composable
fun SongeSynthScreen(
    orchestrator: SynthOrchestrator,
    hazeState: HazeState = remember { HazeState() }
) {
    // Inject ViewModels
    val voiceViewModel: VoiceViewModel = metroViewModel()
    val midiViewModel: MidiViewModel = metroViewModel()
    val presetViewModel: PresetViewModel = metroViewModel()

    val focusRequester = remember { FocusRequester() }

    // Collect states from feature ViewModels
    val voiceState by voiceViewModel.uiState.collectAsState()
    val midiState by midiViewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()

    // Start engine and request focus on composition
    LaunchedEffect(Unit) {
        orchestrator.start()
        focusRequester.requestFocus()
        Logger.info { "Songe Ready \u2713" }
    }

    // Collect peak level from orchestrator's flow
    val peak by orchestrator.peakFlow.collectAsState()

    // Get the selected control ID for learn mode
    val selectedControlId =
        (midiState.mappingState.learnTarget as? LearnTarget.Control)?.controlId

    // Track if a dialog is active to suppress keyboard handling
    var isDialogActive by remember { mutableStateOf(false) }

    // Wrap everything in LearnModeProvider
    LearnModeProvider(
        isActive = midiState.isLearnModeActive,
        selectedControlId = selectedControlId,
        onSelectControl = { controlId ->
            if (midiState.isLearnModeActive) {
                midiViewModel.selectControlForLearning(controlId)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    SynthKeyboardHandler.handleKeyEvent(
                        keyEvent = event,
                        voiceState = voiceState,
                        voiceViewModel = voiceViewModel,
                        isDialogActive = isDialogActive
                    )
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A0A12),
                            Color(0xFF12121A),
                            Color(0xFF0A0A12)
                        )
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // ═══════════════════════════════════════════════════════════
            // TOP ROW: Presets | MIDI | Hyper LFO | Mod Delay | Distortion
            // ═══════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PresetsPanel(
                    presetProps = PresetProps(
                        presets = presetState.presets,
                        selectedPreset = presetState.selectedPreset,
                        onSelect = { presetViewModel.selectPreset(it) },
                        onNew = { presetViewModel.saveNewPreset(it) },
                        onOverride = { presetViewModel.overridePreset() },
                        onDelete = { presetViewModel.deletePreset() },
                        onApply = { presetViewModel.applyPreset(it) },
                        onDialogActiveChange = { isDialogActive = it }
                    ),
                    modifier = Modifier.fillMaxHeight()
                )

                MidiPanel(
                    midiProps = MidiProps(
                        deviceName = midiState.deviceName,
                        isOpen = midiState.isConnected,
                        isLearnModeActive = midiState.isLearnModeActive,
                        onClick = { /* Could open MIDI device selector */ },
                        onLearnToggle = { midiViewModel.toggleLearnMode() },
                        onLearnSave = { midiViewModel.saveLearnedMappings() },
                        onLearnCancel = { midiViewModel.cancelLearnMode() }
                    ),
                    modifier = Modifier.fillMaxHeight()
                )

                HyperLfoPanel(modifier = Modifier.fillMaxHeight())
                ModDelayPanel(modifier = Modifier.fillMaxHeight())
                DistortionPanel(modifier = Modifier.fillMaxHeight())
            }

            // ═══════════════════════════════════════════════════════════
            // MAIN SECTION: Left Group | Center | Right Group
            // ═══════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LEFT GROUP (Voices 1-4)
                VoiceGroupSection(
                    quadLabel = "1-4",
                    quadColor = SongeColors.neonMagenta,
                    voiceStartIndex = 0,
                    voiceViewModel = voiceViewModel,
                    midiViewModel = midiViewModel,
                    modifier = Modifier.weight(1f)
                )

                // CENTER: Cross-mod + Global controls
                CenterControlPanel()

                // RIGHT GROUP (Voices 5-8)
                VoiceGroupSection(
                    quadLabel = "5-8",
                    quadColor = SongeColors.synthGreen,
                    voiceStartIndex = 4,
                    voiceViewModel = voiceViewModel,
                    midiViewModel = midiViewModel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } // LearnModeProvider
}

// ═══════════════════════════════════════════════════════════
// END OF SCREEN
// ═══════════════════════════════════════════════════════════
