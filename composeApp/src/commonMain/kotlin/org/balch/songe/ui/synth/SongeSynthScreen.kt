package org.balch.songe.ui.synth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import org.balch.songe.audio.SongeEngine
import org.balch.songe.input.KeyboardInputHandler
import org.balch.songe.input.LearnTarget
import org.balch.songe.input.MidiMappingState.Companion.ControlIds
import org.balch.songe.ui.components.HorizontalEnvelopeSlider
import org.balch.songe.ui.components.HorizontalSwitch3Way
import org.balch.songe.ui.components.LearnModeProvider
import org.balch.songe.ui.components.LocalLearnModeState
import org.balch.songe.ui.components.PulseButton
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.components.VerticalToggle
import org.balch.songe.ui.components.learnable
import org.balch.songe.ui.panels.DistortionPanel
import org.balch.songe.ui.panels.HyperLfoPanel
import org.balch.songe.ui.panels.MidiPanel
import org.balch.songe.ui.panels.MidiProps
import org.balch.songe.ui.panels.ModDelayPanel
import org.balch.songe.ui.panels.PresetProps
import org.balch.songe.ui.panels.PresetsPanel
import org.balch.songe.ui.preview.PreviewSongeEngine
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.util.Logger
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview(widthDp = 800, heightDp = 600)
@Composable
fun SongeSynthScreen(
    engine: SongeEngine = PreviewSongeEngine(),
    viewModel: SynthViewModel = remember { SynthViewModel(engine) },
    hazeState: HazeState = remember { HazeState() }
) {
    val focusRequester = remember { FocusRequester() }
    
    DisposableEffect(Unit) {
        viewModel.startAudio()
        viewModel.initMidi()
        viewModel.loadPresets()
        Logger.info { "Songe Ready ✓" }
        onDispose {
            viewModel.stopMidi()
            viewModel.stopAudio()
        }
    }
    
    // Request focus on launch to capture keyboard events
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Get the selected control ID for learn mode
    val selectedControlId = (viewModel.midiMappingState.learnTarget as? LearnTarget.Control)?.controlId
    
    // Track if a dialog is active to suppress keyboard handling
    var isDialogActive by remember { mutableStateOf(false) }
    
    // Wrap everything in LearnModeProvider to make learn state available to all controls
    LearnModeProvider(
        isActive = viewModel.isLearnModeActive,
        selectedControlId = selectedControlId,
        onSelectControl = { controlId -> viewModel.selectControlForLearning(controlId) }
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                // Skip keyboard handling when dialog is active
                if (isDialogActive) return@onPreviewKeyEvent false
                
                val key = keyEvent.key
                val isKeyDown = keyEvent.type == KeyEventType.KeyDown
                val isKeyUp = keyEvent.type == KeyEventType.KeyUp
                
                // Handle voice trigger keys (A/S/D/F/G/H/J/K)
                KeyboardInputHandler.getVoiceFromKey(key)?.let { voiceIndex ->
                    if (isKeyDown && !KeyboardInputHandler.isVoiceKeyPressed(voiceIndex)) {
                        KeyboardInputHandler.onVoiceKeyDown(voiceIndex)
                        viewModel.onPulseStart(voiceIndex)
                        return@onPreviewKeyEvent true
                    } else if (isKeyUp) {
                        KeyboardInputHandler.onVoiceKeyUp(voiceIndex)
                        viewModel.onPulseEnd(voiceIndex)
                        return@onPreviewKeyEvent true
                    }
                }
                
                // Handle tune adjustment keys (1-8)
                if (isKeyDown) {
                    KeyboardInputHandler.getTuneVoiceFromKey(key)?.let { voiceIndex ->
                        val currentTune = viewModel.voiceStates[voiceIndex].tune
                        val delta = KeyboardInputHandler.getTuneDelta(keyEvent.isShiftPressed)
                        val newTune = (currentTune + delta).coerceIn(0f, 1f)
                        viewModel.onVoiceTuneChange(voiceIndex, newTune)
                        return@onPreviewKeyEvent true
                    }
                    
                    // Handle octave shift (Z/X)
                    if (KeyboardInputHandler.handleOctaveKey(key)) {
                        return@onPreviewKeyEvent true
                    }
                }
                
                false
            }
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
        // TOP ROW: Presets | MIDI | Hyper LFO | Mod Delay | Distortion
        // ═══════════════════════════════════════════════════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp), // Fixed height to prevent stretching and ensure alignment
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PresetsPanel(
                presetProps = PresetProps(
                    presets = viewModel.presetList,
                    selectedPreset = viewModel.selectedPreset,
                    onSelect = { viewModel.selectPreset(it) },
                    onNew = { viewModel.saveNewPreset(it) },
                    onOverride = { viewModel.overridePreset() },
                    onDelete = { viewModel.deletePreset() },
                    onApply = { viewModel.applyPreset(it) },
                    onDialogActiveChange = { isDialogActive = it }
                ),
                modifier = Modifier.fillMaxHeight()
            )
            
            MidiPanel(
                midiProps = MidiProps(
                    deviceName = viewModel.connectedMidiDeviceName,
                    isOpen = viewModel.isMidiConnected,
                    isLearnModeActive = viewModel.isLearnModeActive,
                    onClick = { /* Could open MIDI device selector */ },
                    onLearnToggle = { viewModel.toggleLearnMode() },
                    onLearnSave = { viewModel.saveLearnedMappings() },
                    onLearnCancel = { viewModel.cancelLearnMode() }
                ),
                modifier = Modifier.fillMaxHeight()
            )

            HyperLfoPanel(
                lfo1Rate = viewModel.hyperLfoA,
                onLfo1RateChange = viewModel::onHyperLfoAChange,
                lfo2Rate = viewModel.hyperLfoB,
                onLfo2RateChange = viewModel::onHyperLfoBChange,
                mode = viewModel.hyperLfoMode,
                onModeChange = viewModel::onHyperLfoModeChange,
                linkEnabled = viewModel.hyperLfoLink,
                onLinkChange = viewModel::onHyperLfoLinkChange,
                modifier = Modifier.fillMaxHeight()
            )

            ModDelayPanel(
                time1 = viewModel.delayTime1,
                onTime1Change = viewModel::onDelayTime1Change,
                mod1 = viewModel.delayMod1,
                onMod1Change = viewModel::onDelayMod1Change,
                time2 = viewModel.delayTime2,
                onTime2Change = viewModel::onDelayTime2Change,
                mod2 = viewModel.delayMod2,
                onMod2Change = viewModel::onDelayMod2Change,
                feedback = viewModel.delayFeedback,
                onFeedbackChange = viewModel::onDelayFeedbackChange,
                mix = viewModel.delayMix,
                onMixChange = viewModel::onDelayMixChange,
                isLfoSource = viewModel.delayModSourceIsLfo,
                onSourceChange = viewModel::onDelayModSourceChange,
                isTriangleWave = viewModel.delayLfoWaveformIsTriangle,
                onWaveformChange = viewModel::onDelayLfoWaveformChange,
                modifier = Modifier.fillMaxHeight()
            )

            DistortionPanel(
                drive = viewModel.drive,
                onDriveChange = { viewModel.onGlobalDriveChange(it) },
                volume = viewModel.masterVolume,
                onVolumeChange = viewModel::onMasterVolumeChange,
                mix = viewModel.distortionMix,
                onMixChange = viewModel::onDistortionMixChange,
                modifier = Modifier.fillMaxHeight()
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
                CrossModSelector(
                    isCrossQuad = viewModel.fmStructureCrossQuad,
                    onToggle = { viewModel.onFmStructureChange(it) }
                )
                RotaryKnob(
                    value = viewModel.totalFeedback, 
                    onValueChange = { viewModel.onTotalFeedbackChange(it) }, 
                    label = "TOTAL FB",
                    controlId = ControlIds.TOTAL_FEEDBACK,
                    size = 32.dp, 
                    progressColor = SongeColors.neonCyan
                )
                RotaryKnob(
                    value = viewModel.vibrato, 
                    onValueChange = { viewModel.onVibratoChange(it) }, 
                    label = "VIB",
                    controlId = ControlIds.VIBRATO,
                    size = 32.dp, 
                    progressColor = SongeColors.neonMagenta
                )
                RotaryKnob(
                    value = viewModel.voiceCoupling, 
                    onValueChange = { viewModel.onVoiceCouplingChange(it) }, 
                    label = "COUPLE",
                    controlId = ControlIds.VOICE_COUPLING,
                    size = 32.dp, 
                    progressColor = SongeColors.warmGlow
                )
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
    } // LearnModeProvider
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
            // Quad Index: voiceStartIndex 0 -> 0, voiceStartIndex 4 -> 1
            val quadIndex = voiceStartIndex / 4
            
            RotaryKnob(
                value = viewModel.quadGroupPitches[quadIndex], 
                onValueChange = { viewModel.onQuadPitchChange(quadIndex, it) }, 
                label = "PITCH",
                controlId = ControlIds.quadPitch(quadIndex),
                size = 28.dp, 
                progressColor = quadColor
            )
            RotaryKnob(
                value = viewModel.quadGroupHolds[quadIndex], 
                onValueChange = { viewModel.onQuadHoldChange(quadIndex, it) }, 
                label = "HOLD",
                controlId = ControlIds.quadHold(quadIndex),
                size = 28.dp, 
                progressColor = SongeColors.warmGlow
            )
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
            .padding(12.dp),
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
            
            // 3-Way Mod Source Switch (Horizontal)
            // Calculate pair index (0,1,2,3)
            val pairIndex = voiceA / 2
            
            HorizontalSwitch3Way(
                state = viewModel.duoModSources[pairIndex],
                onStateChange = { viewModel.onDuoModSourceChange(pairIndex, it) },
                color = color,
                controlId = ControlIds.duoModSource(pairIndex)
            )
        }
        
        // Main Content: Two Voice Columns side-by-side
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // VOICE A COLUMN
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val pairIndex = voiceA / 2
                // Knobs
                VoiceColumnMod(
                    num = voiceA + 1,
                    voiceIndex = voiceA,
                    pairIndex = pairIndex,
                    tune = viewModel.voiceStates[voiceA].tune,
                    onTuneChange = { viewModel.onVoiceTuneChange(voiceA, it) },
                    modDepth = viewModel.voiceModDepths[voiceA],
                    onModDepthChange = { viewModel.onDuoModDepthChange(pairIndex, it) }, // Apply to both voices
                    envSpeed = viewModel.voiceEnvelopeSpeeds[voiceA],
                    onEnvSpeedChange = { viewModel.onVoiceEnvelopeSpeedChange(voiceA, it) }
                )
                
                // Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TriggerBtn(voiceA + 1, viewModel.voiceStates[voiceA].isHolding, { viewModel.onHoldChange(voiceA, it) }, ControlIds.voiceHold(voiceA))
                    PulseButton(
                        onPulseStart = { viewModel.onPulseStart(voiceA) },
                        onPulseEnd = { viewModel.onPulseEnd(voiceA) },
                        size = 28.dp, 
                        label = "",
                        isLearnMode = viewModel.isLearnModeActive,
                        isLearning = viewModel.isVoiceBeingLearned(voiceA),
                        onLearnSelect = { viewModel.selectVoiceForLearning(voiceA) }
                    )
                }
            }

            // VOICE B COLUMN
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Calculate pair index (0-3) from voice A (0,2,4,6)
                val pairIndex = voiceA / 2
                
                // Knobs
                VoiceColumnSharp(
                    num = voiceB + 1,
                    voiceIndex = voiceB,
                    pairIndex = pairIndex,
                    tune = viewModel.voiceStates[voiceB].tune,
                    onTuneChange = { viewModel.onVoiceTuneChange(voiceB, it) },
                    sharpness = viewModel.pairSharpness[pairIndex],
                    onSharpnessChange = { viewModel.onPairSharpnessChange(pairIndex, it) },
                    envSpeed = viewModel.voiceEnvelopeSpeeds[voiceB],
                    onEnvSpeedChange = { viewModel.onVoiceEnvelopeSpeedChange(voiceB, it) }
                )
                
                // Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TriggerBtn(voiceB + 1, viewModel.voiceStates[voiceB].isHolding, { viewModel.onHoldChange(voiceB, it) }, ControlIds.voiceHold(voiceB))
                    PulseButton(
                        onPulseStart = { viewModel.onPulseStart(voiceB) },
                        onPulseEnd = { viewModel.onPulseEnd(voiceB) },
                        size = 28.dp,
                        label = "",
                        isLearnMode = viewModel.isLearnModeActive,
                        isLearning = viewModel.isVoiceBeingLearned(voiceB),
                        onLearnSelect = { viewModel.selectVoiceForLearning(voiceB) }
                    )
                }
            }
        }
    }
}




@Composable
private fun VoiceColumnMod(
    num: Int,
    voiceIndex: Int,
    pairIndex: Int,
    tune: Float,
    onTuneChange: (Float) -> Unit,
    modDepth: Float,
    onModDepthChange: (Float) -> Unit,
    envSpeed: Float,
    onEnvSpeedChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.3f))
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$num", fontSize = 8.sp, color = SongeColors.electricBlue.copy(alpha = 0.6f))
        RotaryKnob(value = modDepth, onValueChange = onModDepthChange, label = "MOD", controlId = ControlIds.voiceFmDepth(voiceIndex), size = 24.dp, progressColor = SongeColors.neonMagenta)
        RotaryKnob(value = tune, onValueChange = onTuneChange, label = "TUNE", controlId = ControlIds.voiceTune(voiceIndex), size = 28.dp, progressColor = SongeColors.neonCyan)
        // Envelope Speed Slider
        HorizontalEnvelopeSlider(
            value = envSpeed,
            onValueChange = onEnvSpeedChange,
            color = SongeColors.neonCyan,
            controlId = ControlIds.voiceEnvelopeSpeed(voiceIndex)
        )
    }
}

@Composable
private fun VoiceColumnSharp(
    num: Int,
    voiceIndex: Int,
    pairIndex: Int,
    tune: Float,
    onTuneChange: (Float) -> Unit,
    sharpness: Float,
    onSharpnessChange: (Float) -> Unit,
    envSpeed: Float,
    onEnvSpeedChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SongeColors.darkVoid.copy(alpha = 0.3f))
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$num", fontSize = 8.sp, color = SongeColors.electricBlue.copy(alpha = 0.6f))
        RotaryKnob(value = sharpness, onValueChange = onSharpnessChange, label = "SHARP", controlId = ControlIds.pairSharpness(pairIndex), size = 24.dp, progressColor = SongeColors.synthGreen)
        RotaryKnob(value = tune, onValueChange = onTuneChange, label = "TUNE", controlId = ControlIds.voiceTune(voiceIndex), size = 28.dp, progressColor = SongeColors.neonCyan)
        // Envelope Speed Slider
        HorizontalEnvelopeSlider(
            value = envSpeed,
            onValueChange = onEnvSpeedChange,
            color = SongeColors.neonCyan,
            controlId = ControlIds.voiceEnvelopeSpeed(voiceIndex)
        )
    }
}

@Composable
private fun TriggerBtn(num: Int, isHolding: Boolean, onHoldChange: (Boolean) -> Unit, controlId: String? = null) {
    val learnState = LocalLearnModeState.current
    val modifier = Modifier
        .width(42.dp) // Slightly wider
        .height(28.dp) // Match PulseButton size
        .then(
            if (controlId != null) Modifier.learnable(controlId, learnState) else Modifier
        )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                // Metallic Gradient
                Brush.verticalGradient(
                    colors = if (isHolding) {
                        listOf(Color(0xFFD0D0D0), Color(0xFFFFFFFFF), Color(0xFFD0D0D0)) // Bright "Lit" Metal
                    } else {
                         listOf(Color(0xFF808080), Color(0xFF505050), Color(0xFF303030)) // Dark Metal
                    }
                )
            )
            .border(
                width = 1.dp, 
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFE0E0E0), Color(0xFF202020))
                ),
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(enabled = !learnState.isActive) { onHoldChange(!isHolding) },
        contentAlignment = Alignment.Center
    ) {
        // Text / Label
        Text(
            text = "$num", 
            fontSize = 12.sp, 
            fontWeight = FontWeight.Bold, 
            color = if (isHolding) Color(0xFF101010) else Color(0xFFAAAAAA)
        )
        
        // Active Indicator (LED style)
        if (isHolding) {
             Box(
                 modifier = Modifier
                    .fillMaxSize()
                    .background(SongeColors.electricBlue.copy(alpha = 0.2f)) // Blue tint overlay
             )
        }
    }
}




@Composable
private fun CrossModSelector(
    isCrossQuad: Boolean = false,
    onToggle: (Boolean) -> Unit = {}
) {
    val activeColor = if (isCrossQuad) SongeColors.neonCyan else Color.White.copy(alpha = 0.3f)
    
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isCrossQuad) SongeColors.neonCyan.copy(alpha = 0.2f) else Color(0xFF1A1A2A))
            .border(1.dp, activeColor, RoundedCornerShape(4.dp))
            .clickable { onToggle(!isCrossQuad) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "34→56", 
            fontSize = 7.sp, 
            color = if (isCrossQuad) SongeColors.neonCyan else Color.White.copy(alpha = 0.4f),
            fontWeight = if (isCrossQuad) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            "78→12", 
            fontSize = 7.sp, 
            color = if (isCrossQuad) SongeColors.neonCyan else Color.White.copy(alpha = 0.4f),
            fontWeight = if (isCrossQuad) FontWeight.Bold else FontWeight.Normal
        )
    }
}
