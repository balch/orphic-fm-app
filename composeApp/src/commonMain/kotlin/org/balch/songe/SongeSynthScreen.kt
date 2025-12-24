package org.balch.songe

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.songe.features.delay.ModDelayPanel
import org.balch.songe.features.distortion.DistortionPanel
import org.balch.songe.features.lfo.HyperLfoPanel
import org.balch.songe.features.midi.MidiPanel
import org.balch.songe.features.presets.PresetsPanel
import org.balch.songe.features.stereo.StereoPanel
import org.balch.songe.features.voice.SynthKeyboardHandler
import org.balch.songe.features.voice.VoiceViewModel
import org.balch.songe.features.voice.ui.VoiceGroupSection
import org.balch.songe.ui.panels.CenterControlPanel
import org.balch.songe.ui.panels.LocalLiquidEffects
import org.balch.songe.ui.panels.LocalLiquidState
import org.balch.songe.ui.panels.VizPanel
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.viz.VizViewModel
import org.balch.songe.ui.widgets.LearnModeProvider
import org.balch.songe.ui.widgets.VizBackground
import org.balch.songe.util.Logger
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview(widthDp = 800, heightDp = 600)
@Composable
fun SongeSynthScreen(
    orchestrator: SynthOrchestrator,
    liquidState: LiquidState = rememberLiquidState()
) {
    val focusRequester = remember { FocusRequester() }

    // Start engine and request focus on composition
    LaunchedEffect(Unit) {
        orchestrator.start()
        focusRequester.requestFocus()
        Logger.info { "Songe Ready \u2713" }
    }

    // Collect peak level from orchestrator's flow
    val peak by orchestrator.peakFlow.collectAsState()

    // Track if a dialog is active to suppress keyboard handling
    var isDialogActive by remember { mutableStateOf(false) }

    val voiceViewModel: VoiceViewModel = metroViewModel()
    val vizViewModel: VizViewModel = metroViewModel()
    val vizState by vizViewModel.uiState.collectAsState()

    // Wrap everything in LearnModeProvider
    LearnModeProvider {
        Box(modifier = Modifier.fillMaxSize()) {
            // Audio-reactive visualization background layer (source for liquid blur)
            // Only enabled when viz is not "off"
            VizBackground(
                modifier = Modifier
                    .fillMaxSize()
                    .liquefiable(liquidState)
            )

            // Provide LiquidState and LiquidEffects to all child panels via CompositionLocal
            CompositionLocalProvider(
                LocalLiquidState provides liquidState,
                LocalLiquidEffects provides vizState.liquidEffects
            ) {
                // Main UI content layer
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            SynthKeyboardHandler.handleKeyEvent(
                                keyEvent = event,
                                voiceState = voiceViewModel.uiState.value,
                                voiceViewModel = voiceViewModel,
                                isDialogActive = isDialogActive
                            )
                        }
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
                        PresetsPanel(modifier = Modifier.fillMaxHeight())
                        MidiPanel(modifier = Modifier.fillMaxHeight())
                        StereoPanel(modifier = Modifier.fillMaxHeight())
                        VizPanel(modifier = Modifier.fillMaxHeight())
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
                            modifier = Modifier.weight(1f)
                        )

                        // CENTER: Cross-mod + Global controls
                        CenterControlPanel()

                        // RIGHT GROUP (Voices 5-8)
                        VoiceGroupSection(
                            quadLabel = "5-8",
                            quadColor = SongeColors.synthGreen,
                            voiceStartIndex = 4,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } // CompositionLocalProvider
        } // Box
    } // LearnModeProvider
}
