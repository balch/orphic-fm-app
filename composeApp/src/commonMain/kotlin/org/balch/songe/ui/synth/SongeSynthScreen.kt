package org.balch.songe.ui.synth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.balch.songe.audio.SongeEngine
import org.balch.songe.ui.components.LfoWaveform
import org.balch.songe.ui.panels.DuoGroupPanel
import org.balch.songe.ui.panels.GlobalControlsPanel
import org.balch.songe.ui.panels.QuadGroupPanel
import org.balch.songe.ui.panels.VoicePanel

@Composable
fun SongeSynthScreen(
    engine: SongeEngine,
    viewModel: SynthViewModel = remember { SynthViewModel(engine) },
    hazeState: HazeState = remember { HazeState() }
) {
    DisposableEffect(Unit) {
        viewModel.startAudio()
        onDispose { viewModel.stopAudio() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            Text("SONGE-8", color = Color.White.copy(alpha = 0.7f))

            // Upper Half: Voices 1-4
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Quad 1 Group Controls
                QuadGroupPanel(
                    groupName = "1-4",
                    pitch = 0.5f,
                    onPitchChange = {},
                    sustain = 0.5f,
                    onSustainChange = {},
                    hazeState = hazeState
                )
                
                // Duo 1-2
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         VoicePanel(
                             voiceIndex = 0,
                             tune = viewModel.voiceStates[0].tune,
                             onTuneChange = { viewModel.onVoiceTuneChange(0, it) },
                             pulseStrength = 0f,
                             onPulseStart = { viewModel.onPulseStart(0) },
                             onPulseEnd = { viewModel.onPulseEnd(0) },
                             isHolding = viewModel.voiceStates[0].isHolding,
                             onHoldChange = { viewModel.onHoldChange(0, it) },
                             hazeState = hazeState
                         )
                         VoicePanel(
                             voiceIndex = 1,
                             tune = viewModel.voiceStates[1].tune,
                             onTuneChange = { viewModel.onVoiceTuneChange(1, it) },
                             pulseStrength = 0f,
                             onPulseStart = { viewModel.onPulseStart(1) },
                             onPulseEnd = { viewModel.onPulseEnd(1) },
                             isHolding = viewModel.voiceStates[1].isHolding,
                             onHoldChange = { viewModel.onHoldChange(1, it) },
                             hazeState = hazeState
                         )
                     }
                     DuoGroupPanel(
                         voices = 0 to 1,
                         modDepth = 0.2f,
                         onModDepthChange = {},
                         lfoEnabled = false,
                         onLfoEnabledChange = {},
                         lfoWaveform = LfoWaveform.TRIANGLE,
                         onLfoWaveformChange = {},
                         hazeState = hazeState
                     )
                }
                
                // Duo 3-4
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         VoicePanel(
                             voiceIndex = 2,
                             tune = viewModel.voiceStates[2].tune,
                             onTuneChange = { viewModel.onVoiceTuneChange(2, it) },
                             pulseStrength = 0f,
                             onPulseStart = { viewModel.onPulseStart(2) },
                             onPulseEnd = { viewModel.onPulseEnd(2) },
                             isHolding = viewModel.voiceStates[2].isHolding,
                             onHoldChange = { viewModel.onHoldChange(2, it) },
                             hazeState = hazeState
                         )
                         VoicePanel(
                             voiceIndex = 3,
                             tune = viewModel.voiceStates[3].tune,
                             onTuneChange = { viewModel.onVoiceTuneChange(3, it) },
                             pulseStrength = 0f,
                             onPulseStart = { viewModel.onPulseStart(3) },
                             onPulseEnd = { viewModel.onPulseEnd(3) },
                             isHolding = viewModel.voiceStates[3].isHolding,
                             onHoldChange = { viewModel.onHoldChange(3, it) },
                             hazeState = hazeState
                         )
                     }
                     DuoGroupPanel(
                         voices = 2 to 3,
                         modDepth = 0.2f,
                         onModDepthChange = {},
                         lfoEnabled = false,
                         onLfoEnabledChange = {},
                         lfoWaveform = LfoWaveform.TRIANGLE,
                         onLfoWaveformChange = {},
                         hazeState = hazeState
                     )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // Middle: Global Controls
            GlobalControlsPanel(
                vibrato = viewModel.vibrato,
                onVibratoChange = { viewModel.vibrato = it },
                distortion = viewModel.distortion,
                onDistortionChange = { viewModel.onGlobalDistortionChange(it) },
                masterVolume = viewModel.masterVolume,
                onMasterVolumeChange = { viewModel.masterVolume = it },
                pan = 0.5f,
                onPanChange = {},
                masterDrive = viewModel.drive,
                onMasterDriveChange = { viewModel.onGlobalDriveChange(it) },
                hazeState = hazeState,
                modifier = Modifier.fillMaxWidth(0.8f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            // Lower Half: Voices 5-8 (Similar structure to top)
             Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Duo 5-6
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         VoicePanel(
                             voiceIndex = 4,
                             tune = viewModel.voiceStates[4].tune,
                             onTuneChange = { viewModel.onVoiceTuneChange(4, it) },
                             pulseStrength = 0f,
                             onPulseStart = { viewModel.onPulseStart(4) },
                             onPulseEnd = { viewModel.onPulseEnd(4) },
                             isHolding = viewModel.voiceStates[4].isHolding,
                             onHoldChange = { viewModel.onHoldChange(4, it) },
                             hazeState = hazeState
                         )
                         VoicePanel(
                             voiceIndex = 5,
                             tune = viewModel.voiceStates[5].tune,
                             onTuneChange = { viewModel.onVoiceTuneChange(5, it) },
                             pulseStrength = 0f,
                             onPulseStart = { viewModel.onPulseStart(5) },
                             onPulseEnd = { viewModel.onPulseEnd(5) },
                             isHolding = viewModel.voiceStates[5].isHolding,
                             onHoldChange = { viewModel.onHoldChange(5, it) },
                             hazeState = hazeState
                         )
                     }
                     DuoGroupPanel(
                         voices = 4 to 5,
                         modDepth = 0.2f,
                         onModDepthChange = {},
                         lfoEnabled = false,
                         onLfoEnabledChange = {},
                         lfoWaveform = LfoWaveform.TRIANGLE,
                         onLfoWaveformChange = {},
                         hazeState = hazeState
                     )
                }
                
                // Duo 7-8
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         VoicePanel(
                             voiceIndex = 6,
                             tune = viewModel.voiceStates[6].tune,
                             onTuneChange = { viewModel.onVoiceTuneChange(6, it) },
                             pulseStrength = 0f,
                             onPulseStart = { viewModel.onPulseStart(6) },
                             onPulseEnd = { viewModel.onPulseEnd(6) },
                             isHolding = viewModel.voiceStates[6].isHolding,
                             onHoldChange = { viewModel.onHoldChange(6, it) },
                             hazeState = hazeState
                         )
                         VoicePanel(
                             voiceIndex = 7,
                             tune = viewModel.voiceStates[7].tune,
                             onTuneChange = { viewModel.onVoiceTuneChange(7, it) },
                             pulseStrength = 0f,
                             onPulseStart = { viewModel.onPulseStart(7) },
                             onPulseEnd = { viewModel.onPulseEnd(7) },
                             isHolding = viewModel.voiceStates[7].isHolding,
                             onHoldChange = { viewModel.onHoldChange(7, it) },
                             hazeState = hazeState
                         )
                     }
                     DuoGroupPanel(
                         voices = 6 to 7,
                         modDepth = 0.2f,
                         onModDepthChange = {},
                         lfoEnabled = false,
                         onLfoEnabledChange = {},
                         lfoWaveform = LfoWaveform.TRIANGLE,
                         onLfoWaveformChange = {},
                         hazeState = hazeState
                     )
                }
                
                // Quad 5-8 Group Controls
                QuadGroupPanel(
                    groupName = "5-8",
                    pitch = 0.5f,
                    onPitchChange = {},
                    sustain = 0.5f,
                    onSustainChange = {},
                    hazeState = hazeState
                )
            }
        }
    }
}
