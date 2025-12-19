package org.balch.songe.ui.synth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.balch.songe.audio.SongeEngine
import org.balch.songe.audio.VoiceState
import org.balch.songe.util.Logger

class SynthViewModel(
    private val engine: SongeEngine
) : ViewModel() {

    // 8 Voices
    var voiceStates by mutableStateOf(List(8) { index -> VoiceState(index = index) })
        private set

    // Duo Groups (1-2, 3-4, 5-6, 7-8)
    private var duoGroupStates by mutableStateOf(List(4) { 0.0f }) // Mod Depth

    // Quad Groups (1-4, 5-8)
    private var quadGroupPitches by mutableStateOf(List(2) { 0.5f })
    private var quadGroupSustains by mutableStateOf(List(2) { 0.5f }) // Placeholder

    // Global
    var masterVolume by mutableStateOf(0.7f)
    var delayTime by mutableStateOf(0.3f)
    var delayFeedback by mutableStateOf(0.5f) // Added tracking
    var distortion by mutableStateOf(0.0f)
    var drive by mutableStateOf(0.0f)
    var vibrato by mutableStateOf(0.0f)

    // Hyper LFO
    var hyperLfoA by mutableStateOf(0.5f)
    var hyperLfoB by mutableStateOf(0.3f)
    var hyperLfoMode by mutableStateOf(org.balch.songe.ui.panels.HyperLfoMode.AND)
    var hyperLfoLink by mutableStateOf(false)

    fun onVoiceTuneChange(index: Int, newTune: Float) {
        val newVoices = voiceStates.toMutableList()
        newVoices[index] = newVoices[index].copy(tune = newTune)
        voiceStates = newVoices
        engine.setVoiceTune(index, newTune)
    }

    // ... (Pulse/Hold methods are unchanged)

    // Hyper LFO Handlers
    fun onHyperLfoAChange(v: Float) {
        hyperLfoA = v
        engine.setHyperLfoFreq(0, v)
    }
    
    fun onHyperLfoBChange(v: Float) {
        hyperLfoB = v
        engine.setHyperLfoFreq(1, v)
    }
    
    fun onHyperLfoModeChange(mode: org.balch.songe.ui.panels.HyperLfoMode) {
        hyperLfoMode = mode
        engine.setHyperLfoMode(mode == org.balch.songe.ui.panels.HyperLfoMode.AND)
    }
    
    fun onHyperLfoLinkChange(enabled: Boolean) {
        hyperLfoLink = enabled
        engine.setHyperLfoLink(enabled)
    }

    fun onPulseStart(index: Int) {
        val newVoices = voiceStates.toMutableList()
        newVoices[index] = newVoices[index].copy(pulse = true)
        voiceStates = newVoices
        engine.setVoiceGate(index, true)
        // Trigger visual pulse or feedback here if needed
    }

    fun onPulseEnd(index: Int) {
         val newVoices = voiceStates.toMutableList()
        newVoices[index] = newVoices[index].copy(pulse = false)
        voiceStates = newVoices
        if (!voiceStates[index].isHolding) {
             engine.setVoiceGate(index, false)
        }
    }

    fun onHoldChange(index: Int, holding: Boolean) {
        val newVoices = voiceStates.toMutableList()
        newVoices[index] = newVoices[index].copy(isHolding = holding)
        voiceStates = newVoices
        
        // If holding is turned ON, gate is ON.
        // If holding is turned OFF, gate is OFF (unless pulse is pressed, but UI usually handles touch separately)
        if (holding) {
             engine.setVoiceGate(index, true)
        } else if (!newVoices[index].pulse) {
             engine.setVoiceGate(index, false)
        }
    }
    
    // Group & Global setters...
    fun onGlobalDriveChange(v: Float) {
        drive = v
        engine.setDrive(v)
        Logger.info("Global Drive: $v")
    }
    
     fun onGlobalDistortionChange(v: Float) {
        distortion = v
        // engine.setDistortion(v)
        Logger.info("Global Distortion: $v")
    }
    
    // Lifecycle
    fun startAudio() {
        engine.start()
    }
    
    fun stopAudio() {
        engine.stop()
    }
}
