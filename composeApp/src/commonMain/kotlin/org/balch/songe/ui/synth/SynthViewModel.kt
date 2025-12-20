package org.balch.songe.ui.synth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.balch.songe.audio.SongeEngine
import org.balch.songe.audio.VoiceState
import org.balch.songe.util.Logger

class SynthViewModel(
    private val engine: SongeEngine
) : ViewModel() {

    // 8 Voices
    var voiceStates by mutableStateOf(List(8) { index -> VoiceState(index = index) })
        private set
    
    // Voice MOD Depths - only for ODD voices (1,3,5,7 = indices 0,2,4,6)
    var voiceModDepths by mutableStateOf(List(8) { 0.0f })
        private set
    
    // Pair Sharpness (Triangle->Square) - controls EVEN voice knobs (2,4,6,8)
    // Index 0 = Pair 1-2, Index 1 = Pair 3-4, etc.
    var pairSharpness by mutableStateOf(List(4) { 0.0f })
        private set
    
    // Voice Envelope Modes (true=Fast, false=Slow)
    var voiceEnvelopeModes by mutableStateOf(List(8) { false }) // Default: Slow/Drone
        private set

    // Duo Groups (1-2, 3-4, 5-6, 7-8)
    private var duoGroupStates by mutableStateOf(List(4) { 0.0f }) // Mod Depth

    // Quad Groups (1-4, 5-8)
    var quadGroupPitches by mutableStateOf(List(2) { 0.5f }) // 0.5 = Unity
        private set
    var quadGroupHolds by mutableStateOf(List(2) { 0.0f }) // 0.0 = Silence
        private set

    // Global
    var masterVolume by mutableStateOf(0.7f)
    var delayFeedback by mutableStateOf(0.5f)
    var delayMix by mutableStateOf(0.5f)
    var distortion by mutableStateOf(0.0f)
    var drive by mutableStateOf(0.0f)
    var distortionMix by mutableStateOf(0.5f) // 0=clean, 1=distorted (Lyra MIX)
    
    // Delay Lines (1 & 2)
    var delayTime1 by mutableStateOf(0.3f)
    var delayTime2 by mutableStateOf(0.3f)
    var delayMod1 by mutableStateOf(0.0f)
    var delayMod2 by mutableStateOf(0.0f)
    var delayModSourceIsLfo by mutableStateOf(true) // true=LFO, false=SELF (Global switch for now, or per channel?)
    var delayLfoWaveformIsTriangle by mutableStateOf(true) // true=Triangle, false=Square (AND)

    // Hyper LFO
    var hyperLfoA by mutableStateOf(0.5f)
    var hyperLfoB by mutableStateOf(0.3f)
    var hyperLfoMode by mutableStateOf(org.balch.songe.ui.panels.HyperLfoMode.AND)
    var hyperLfoLink by mutableStateOf(false)
    
    // Duo Mod Sources (4 pairs)
    // Default: OFF (center) as per request "Central position means modulation for a group is turned off"
    // Or LFO (down) if preferred default. Let's start OFF.
    var duoModSources by mutableStateOf(List(4) { org.balch.songe.audio.ModSource.OFF })
        private set
    
    // Advanced FM
    var fmStructureCrossQuad by mutableStateOf(false) // false = within-pair, true = cross-quad
        private set
    var totalFeedback by mutableStateOf(0.0f) // 0-1, output→LFO feedback
        private set
    var vibrato by mutableStateOf(0.0f) // 0-1, global pitch wobble
        private set
    var voiceCoupling by mutableStateOf(0.0f) // 0-1, partner envelope->frequency
        private set

    fun onVoiceTuneChange(index: Int, newTune: Float) {
        val newVoices = voiceStates.toMutableList()
        newVoices[index] = newVoices[index].copy(tune = newTune)
        voiceStates = newVoices
        engine.setVoiceTune(index, newTune)
    }
    
    fun onVoiceModDepthChange(index: Int, newDepth: Float) {
        val newDepths = voiceModDepths.toMutableList()
        newDepths[index] = newDepth
        voiceModDepths = newDepths
        engine.setVoiceFmDepth(index, newDepth)
    }
    
    // Apply FM depth to both voices in a Duo
    fun onDuoModDepthChange(duoIndex: Int, newDepth: Float) {
        val voiceA = duoIndex * 2
        val voiceB = voiceA + 1
        onVoiceModDepthChange(voiceA, newDepth)
        onVoiceModDepthChange(voiceB, newDepth)
    }
    
    fun onPairSharpnessChange(pairIndex: Int, newSharpness: Float) {
        val newSharpnessList = pairSharpness.toMutableList()
        newSharpnessList[pairIndex] = newSharpness
        pairSharpness = newSharpnessList
        engine.setPairSharpness(pairIndex, newSharpness)
    }
    
    fun onVoiceEnvelopeModeChange(index: Int, isFast: Boolean) {
        val newModes = voiceEnvelopeModes.toMutableList()
        newModes[index] = isFast
        voiceEnvelopeModes = newModes
        engine.setVoiceEnvelopeMode(index, isFast)
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
    
    fun onDuoModSourceChange(index: Int, source: org.balch.songe.audio.ModSource) {
        val newSources = duoModSources.toMutableList()
        newSources[index] = source
        duoModSources = newSources
        engine.setDuoModSource(index, source)
    }
    
    // Delay Handlers
    fun onDelayTime1Change(v: Float) {
        delayTime1 = v
        engine.setDelayTime(0, v)
    }
    
    fun onDelayTime2Change(v: Float) {
        delayTime2 = v
        engine.setDelayTime(1, v)
    }
    
    fun onDelayMod1Change(v: Float) {
        delayMod1 = v
        engine.setDelayModDepth(0, v)
    }
    
    fun onDelayMod2Change(v: Float) {
        delayMod2 = v
        engine.setDelayModDepth(1, v)
    }
    
    fun onDelayFeedbackChange(v: Float) {
        delayFeedback = v
        engine.setDelayFeedback(v)
    }
    
    fun onDelayMixChange(v: Float) {
        delayMix = v
        engine.setDelayMix(v)
    }
    
    fun onDelayModSourceChange(isLfo: Boolean) {
        delayModSourceIsLfo = isLfo
        // Apply to both delays for now as per UI Toggle
        engine.setDelayModSource(0, isLfo)
        engine.setDelayModSource(1, isLfo)
    }
    
    fun onDelayLfoWaveformChange(isTriangle: Boolean) {
        delayLfoWaveformIsTriangle = isTriangle
        engine.setDelayLfoWaveform(isTriangle)
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
    fun onGlobalDriveChange(newDrive: Float) {
        drive = newDrive
        engine.setDrive(newDrive)
    }
    
    fun onMasterVolumeChange(newVolume: Float) {
        masterVolume = newVolume
        engine.setMasterVolume(newVolume)
    }
    
     fun onGlobalDistortionChange(v: Float) {
        distortion = v
        // engine.setDistortion(v)
        Logger.info { "Global Distortion: $v" }
    }
    
    fun onDistortionMixChange(amount: Float) {
        distortionMix = amount
        engine.setDistortionMix(amount)
    }
    
    fun onQuadPitchChange(index: Int, pitch: Float) {
        val newPitches = quadGroupPitches.toMutableList()
        newPitches[index] = pitch
        quadGroupPitches = newPitches
        engine.setQuadPitch(index, pitch)
    }
    
    fun onQuadHoldChange(index: Int, amount: Float) {
        val newHolds = quadGroupHolds.toMutableList()
        newHolds[index] = amount
        quadGroupHolds = newHolds
        engine.setQuadHold(index, amount)
    }

    fun onFmStructureChange(crossQuad: Boolean) {
        fmStructureCrossQuad = crossQuad
        engine.setFmStructure(crossQuad)
        // Re-apply all Duo mod sources with new structure
        duoModSources.forEachIndexed { index, source ->
            if (source == org.balch.songe.audio.ModSource.VOICE_FM) {
                engine.setDuoModSource(index, source)
            }
        }
        Logger.debug { "FM Structure: ${if (crossQuad) "Cross-Quad" else "Within-Pair"}" }
    }
    
    fun onTotalFeedbackChange(amount: Float) {
        totalFeedback = amount
        engine.setTotalFeedback(amount)
    }
    
    fun onVibratoChange(amount: Float) {
        vibrato = amount
        engine.setVibrato(amount)
    }
    
    fun onVoiceCouplingChange(amount: Float) {
        voiceCoupling = amount
        engine.setVoiceCoupling(amount)
    }

    // Lifecycle
    fun startAudio() {
        engine.start()
    }
    
    fun stopAudio() {
        engine.stop()
    }
}
