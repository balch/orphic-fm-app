package org.balch.songe.ui.synth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import org.balch.songe.audio.SongeEngine
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.panels.HyperLfoMode
import org.balch.songe.ui.panels.HyperLfoPanel
import org.balch.songe.ui.preview.PreviewSongeEngine
import org.balch.songe.ui.theme.SongeColors
import org.jetbrains.compose.ui.tooling.preview.Preview
import javax.swing.text.View

@Preview(widthDp = 600, heightDp = 480)
@Composable
fun SongeSynthScreen(
    engine: SongeEngine = PreviewSongeEngine(),
    viewModel: SynthViewModel = remember { SynthViewModel(engine) },
    hazeState: HazeState = remember { HazeState() }
) {
    DisposableEffect(Unit) {
        viewModel.startAudio()
        onDispose { viewModel.stopAudio() }
    }

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
        verticalArrangement = Arrangement.Top
    ) {
        // ═══════════════════════════════════════════════════════════
        // TOP ROW: Hyper LFO | Mod Delay | Distortion
        // ═══════════════════════════════════════════════════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HyperLfoPanel(
                lfo1Rate = viewModel.vibrato,
                onLfo1RateChange = { viewModel.vibrato = it },
                lfo2Rate = 0.3f,
                onLfo2RateChange = {},
                mode = HyperLfoMode.AND,
                onModeChange = {},
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(.75f)
            )
            ModDelaySection(modifier = Modifier
                .weight(1f).fillMaxHeight())
            DistortionSection(
                drive = viewModel.drive,
                onDriveChange = { viewModel.onGlobalDriveChange(it) },
                volume = viewModel.masterVolume,
                onVolumeChange = { viewModel.masterVolume = it },
                modifier = Modifier
                    .padding(end = 16.dp, start = 32.dp)
                    .fillMaxHeight()
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
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Centered Quad Header
        Text(quadLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = quadColor)
        
        // PITCH and HOLD centered below
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(value = 0.5f, onValueChange = {}, label = "PITCH", size = 28.dp, progressColor = quadColor)
            RotaryKnob(value = 0.5f, onValueChange = {}, label = "HOLD", size = 28.dp, progressColor = SongeColors.warmGlow)
        }
        
        // Two Duo groups side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DuoPairBox(
                voiceA = voiceStartIndex,
                voiceB = voiceStartIndex + 1,
                color = duoColors[0],
                viewModel = viewModel,
                modifier = Modifier.weight(1f)
            )
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
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Full-width header bar with Duo label and LFO toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${voiceA + 1}-${voiceB + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
            
            LFOToggleSwitch(color = color)
        }
        
        // Voice pair: Left = MOD, Right = SHARP
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            VoiceColumnMod(voiceA + 1, viewModel.voiceStates[voiceA].tune) { viewModel.onVoiceTuneChange(voiceA, it) }
            VoiceColumnSharp(voiceB + 1, viewModel.voiceStates[voiceB].tune) { viewModel.onVoiceTuneChange(voiceB, it) }
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
private fun LFOToggleSwitch(
    color: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("LFO", fontSize = 8.sp, color = color.copy(alpha = 0.7f))
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color.copy(alpha = 0.3f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(1.dp)
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun VerticalLFOToggle(
    color: Color,
    isOn: Boolean = false,
    onToggle: (Boolean) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, if (isOn) color else color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .clickable { onToggle(!isOn) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("LFO", fontSize = 8.sp, color = if (isOn) color else color.copy(alpha = 0.5f))

        // Vertical switch
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (isOn) color.copy(alpha = 0.6f) else color.copy(alpha = 0.2f)),
            contentAlignment = if (isOn) Alignment.BottomCenter else Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(1.dp)
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isOn) Color.White else Color.White.copy(alpha = 0.4f))
            )
        }
    }
}

@Composable
private fun VoiceColumnMod(num: Int, tune: Float, onTuneChange: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.3f))
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$num", fontSize = 8.sp, color = SongeColors.electricBlue.copy(alpha = 0.6f))
        RotaryKnob(value = 0.5f, onValueChange = {}, label = "MOD", size = 24.dp, progressColor = SongeColors.neonMagenta)
        RotaryKnob(value = tune, onValueChange = onTuneChange, label = "TUNE", size = 28.dp, progressColor = SongeColors.neonCyan)
    }
}

@Composable
private fun VoiceColumnSharp(num: Int, tune: Float, onTuneChange: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.3f))
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$num", fontSize = 8.sp, color = SongeColors.electricBlue.copy(alpha = 0.6f))
        RotaryKnob(value = 0.5f, onValueChange = {}, label = "SHARP", size = 24.dp, progressColor = SongeColors.synthGreen)
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
private fun ModDelaySection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.5f))
            .border(1.dp, SongeColors.warmGlow.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Title at TOP
        Text("MOD DELAY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SongeColors.warmGlow)
        
        // SELF/LFO toggles
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("SELF", isSelected = true, color = SongeColors.warmGlow)
            ToggleChip("LFO", isSelected = false, color = SongeColors.warmGlow)
        }
        
        // MOD knobs row with vertical LFO toggles
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(value = 0.3f, onValueChange = {}, label = "MOD 1", size = 32.dp, progressColor = SongeColors.warmGlow)
            RotaryKnob(value = 0.3f, onValueChange = {}, label = "MOD 2", size = 32.dp, progressColor = SongeColors.warmGlow)
            VerticalLFOToggle(color = SongeColors.warmGlow)
            VerticalLFOToggle(color = SongeColors.warmGlow)
        }
        
        // TIME/FB/MIX knobs row
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RotaryKnob(value = 0.3f, onValueChange = {}, label = "TIME 1", size = 32.dp, progressColor = SongeColors.warmGlow)
            RotaryKnob(value = 0.3f, onValueChange = {}, label = "TIME 2", size = 32.dp, progressColor = SongeColors.warmGlow)
            RotaryKnob(value = 0.4f, onValueChange = {}, label = "FB", size = 32.dp, progressColor = SongeColors.warmGlow)
            RotaryKnob(value = 0.3f, onValueChange = {}, label = "MIX", size = 32.dp, progressColor = SongeColors.warmGlow)
        }
    }
}

@Composable
private fun ToggleChip(text: String, isSelected: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) color.copy(alpha = 0.3f) else Color(0xFF2A2A3A))
            .border(1.dp, if (isSelected) color else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 8.sp, color = if (isSelected) color else Color.White.copy(alpha = 0.5f))
    }
}

@Composable
private fun DistortionSection(drive: Float, onDriveChange: (Float) -> Unit, volume: Float, onVolumeChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.5f))
            .border(1.dp, SongeColors.neonMagenta.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Title at TOP
        Text(
            "DISTORTION",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = SongeColors.neonMagenta
        )

        // Spacer to push knobs down
        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            RotaryKnob(
                value = drive,
                onValueChange = onDriveChange,
                label = "DRIVE",
                size = 42.dp,
                progressColor = SongeColors.neonMagenta
            )
            RotaryKnob(
                value = volume,
                onValueChange = onVolumeChange,
                label = "VOLUME",
                size = 42.dp,
                progressColor = SongeColors.electricBlue
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            RotaryKnob(
                value = drive,
                onValueChange = onDriveChange,
                label = "PAN",
                size = 42.dp,
                progressColor = SongeColors.neonMagenta
            )
            Canvas(
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.CenterVertically)
            ) { // Size the canvas
                drawCircle(
                    color = Color.Red, // Set the fill color
                    radius = size.minDimension / 2f // Radius to fill the canvas
                    // Center is automatically the center of the Canvas
                )
            }
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
