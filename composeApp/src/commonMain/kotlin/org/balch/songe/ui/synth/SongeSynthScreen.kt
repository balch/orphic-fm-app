package org.balch.songe.ui.synth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import org.balch.songe.audio.SongeEngine
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.panels.HyperLfoMode
import org.balch.songe.ui.panels.HyperLfoPanel
import org.balch.songe.ui.theme.SongeColors

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A0A12), Color(0xFF12121A), Color(0xFF0A0A12))
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ═══════════════════════════════════════════════════════════
            // TOP ROW: Hyper LFO | Mod Delay | Distortion
            // ═══════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                HyperLfoPanel(
                    lfo1Rate = viewModel.vibrato,
                    onLfo1RateChange = { viewModel.vibrato = it },
                    lfo2Rate = 0.3f,
                    onLfo2RateChange = {},
                    mode = HyperLfoMode.AND,
                    onModeChange = {}
                )
                ModDelaySection()
                DistortionSection(
                    drive = viewModel.drive,
                    onDriveChange = { viewModel.onGlobalDriveChange(it) },
                    volume = viewModel.masterVolume,
                    onVolumeChange = { viewModel.masterVolume = it }
                )
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
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f)
                )
                
                // CENTER: Cross-mod + Global controls
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SongeColors.darkVoid.copy(alpha = 0.4f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("SONGE-8", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                    CrossModSelector()
                    RotaryKnob(value = 0.5f, onValueChange = {}, label = "TOTAL FB", size = 32.dp, progressColor = SongeColors.neonCyan)
                    RotaryKnob(value = viewModel.vibrato, onValueChange = { viewModel.vibrato = it }, label = "VIB", size = 32.dp, progressColor = SongeColors.neonMagenta)
                }
                
                // RIGHT GROUP (Voices 5-8)
                VoiceGroupSection(
                    quadLabel = "5-8",
                    quadColor = SongeColors.synthGreen,
                    voiceStartIndex = 4,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// PRIVATE COMPOSABLES
// ═══════════════════════════════════════════════════════════

@Composable
private fun VoiceGroupSection(
    quadLabel: String,
    quadColor: Color,
    voiceStartIndex: Int,
    viewModel: SynthViewModel,
    modifier: Modifier = Modifier
) {
    val duoColors = if (voiceStartIndex == 0) {
        listOf(SongeColors.neonMagenta, SongeColors.warmGlow) // 1-2, 3-4
    } else {
        listOf(SongeColors.neonCyan, SongeColors.synthGreen) // 5-6, 7-8
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.5f))
            .border(1.dp, quadColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header with Quad controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(quadLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = quadColor)
            RotaryKnob(value = 0.5f, onValueChange = {}, label = "PITCH", size = 28.dp, progressColor = quadColor)
            RotaryKnob(value = 0.5f, onValueChange = {}, label = "HOLD", size = 28.dp, progressColor = SongeColors.warmGlow)
        }
        
        // Two Duo groups side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // First Duo (e.g., 1-2)
            DuoPairBox(
                voiceA = voiceStartIndex,
                voiceB = voiceStartIndex + 1,
                color = duoColors[0],
                viewModel = viewModel,
                modifier = Modifier.weight(1f)
            )
            
            // Second Duo (e.g., 3-4)
            DuoPairBox(
                voiceA = voiceStartIndex + 2,
                voiceB = voiceStartIndex + 3,
                color = duoColors[1],
                viewModel = viewModel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DuoPairBox(
    voiceA: Int,
    voiceB: Int,
    color: Color,
    viewModel: SynthViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.4f))
            .border(2.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Duo label
        Text("${voiceA + 1}-${voiceB + 1}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
        
        // Voice pair
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            VoiceColumn(voiceA + 1, viewModel.voiceStates[voiceA].tune) { viewModel.onVoiceTuneChange(voiceA, it) }
            VoiceColumn(voiceB + 1, viewModel.voiceStates[voiceB].tune) { viewModel.onVoiceTuneChange(voiceB, it) }
        }
        
        // Trigger buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TriggerBtn(voiceA + 1, viewModel.voiceStates[voiceA].isHolding) { viewModel.onHoldChange(voiceA, it) }
            TriggerBtn(voiceB + 1, viewModel.voiceStates[voiceB].isHolding) { viewModel.onHoldChange(voiceB, it) }
        }
    }
}

@Composable
private fun VoiceColumn(num: Int, tune: Float, onTuneChange: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.3f))
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$num", fontSize = 8.sp, color = SongeColors.electricBlue.copy(alpha = 0.6f))
        RotaryKnob(value = 0.5f, onValueChange = {}, label = "SHP", size = 24.dp, progressColor = SongeColors.synthGreen)
        RotaryKnob(value = tune, onValueChange = onTuneChange, label = "TUNE", size = 28.dp, progressColor = SongeColors.neonCyan)
    }
}

@Composable
private fun TriggerBtn(num: Int, isHolding: Boolean, onHoldChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHolding) SongeColors.electricBlue else Color(0xFF2A2A3A))
            .border(1.dp, if (isHolding) SongeColors.electricBlue else Color(0xFF4A4A5A), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("$num", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isHolding) Color.White else Color(0xFF888888))
    }
}

@Composable
private fun ModDelaySection() {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.5f))
            .border(1.dp, SongeColors.warmGlow.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("MOD DELAY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SongeColors.warmGlow)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RotaryKnob(value = 0.3f, onValueChange = {}, label = "T1", size = 28.dp, progressColor = SongeColors.warmGlow)
            RotaryKnob(value = 0.3f, onValueChange = {}, label = "T2", size = 28.dp, progressColor = SongeColors.warmGlow)
            RotaryKnob(value = 0.4f, onValueChange = {}, label = "FB", size = 28.dp, progressColor = SongeColors.warmGlow)
            RotaryKnob(value = 0.3f, onValueChange = {}, label = "MIX", size = 28.dp, progressColor = SongeColors.warmGlow)
        }
    }
}

@Composable
private fun DistortionSection(drive: Float, onDriveChange: (Float) -> Unit, volume: Float, onVolumeChange: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.5f))
            .border(1.dp, SongeColors.neonMagenta.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("DIST", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SongeColors.neonMagenta)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RotaryKnob(value = drive, onValueChange = onDriveChange, label = "DRIVE", size = 32.dp, progressColor = SongeColors.neonMagenta)
            RotaryKnob(value = volume, onValueChange = onVolumeChange, label = "VOL", size = 32.dp, progressColor = SongeColors.electricBlue)
        }
    }
}

@Composable
private fun CrossModSelector() {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1A1A2A))
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("34→56", fontSize = 7.sp, color = Color.White.copy(alpha = 0.4f))
        Text("78→12", fontSize = 7.sp, color = Color.White.copy(alpha = 0.4f))
    }
}
