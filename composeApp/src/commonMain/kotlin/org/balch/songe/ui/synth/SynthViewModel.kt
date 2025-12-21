package org.balch.songe.ui.synth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.balch.songe.audio.SongeEngine
import org.balch.songe.audio.VoiceState
import org.balch.songe.input.MidiController
import org.balch.songe.input.MidiEventListener
import org.balch.songe.input.MidiMappingState
import org.balch.songe.util.Logger

class SynthViewModel(
    private val engine: SongeEngine,
    val midiController: MidiController = MidiController()
) : ViewModel() {

    // 8 Voices - Default tunings set to F# minor chord (F#, A, C#, E, F#, A, C#, F#)
    // Tune values map 0-1 to frequency range. These values create an F#min7 spread.
    private val fSharpMinorTunings = listOf(
        0.20f,  // Voice 1: F#2
        0.27f,  // Voice 2: A2
        0.34f,  // Voice 3: C#3
        0.40f,  // Voice 4: E3 (minor 7th)
        0.47f,  // Voice 5: F#3
        0.54f,  // Voice 6: A3
        0.61f,  // Voice 7: C#4
        0.68f   // Voice 8: F#4
    )
    var voiceStates by mutableStateOf(List(8) { index -> VoiceState(index = index, tune = fSharpMinorTunings[index]) })
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
    var hyperLfoA by mutableStateOf(0.0f)
    var hyperLfoB by mutableStateOf(0.0f)
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
    var voiceCoupling by mutableStateOf(0.0f) // 0-1, partner envelope→frequency
        private set
    
    // MIDI Mapping
    var midiMappingState by mutableStateOf(MidiMappingState())
        private set
    
    var showMidiMappingDialog by mutableStateOf(false)
    
    // MIDI Event Listener
    private val midiEventListener = object : MidiEventListener {
        override fun onNoteOn(note: Int, velocity: Int) {
            // Check if we're in learn mode
            midiMappingState.learnMode?.let { voiceIndex ->
                midiMappingState = midiMappingState.assignNote(note, voiceIndex)
                Logger.info { "Assigned MIDI note ${MidiMappingState.noteName(note)} to Voice ${voiceIndex + 1}" }
                return
            }
            
            // Normal operation: trigger voice based on mapping
            midiMappingState.getVoiceForNote(note)?.let { voiceIndex ->
                onPulseStart(voiceIndex)
            }
        }
        
        override fun onNoteOff(note: Int) {
            midiMappingState.getVoiceForNote(note)?.let { voiceIndex ->
                onPulseEnd(voiceIndex)
            }
        }
        
        override fun onControlChange(controller: Int, value: Int) {
            // CC1 = Mod wheel → could map to vibrato or coupling
            if (controller == 1) {
                val normalized = value / 127f
                onVibratoChange(normalized)
            }
        }
        
        override fun onPitchBend(value: Int) {
            // Could apply to quad pitch or other parameter
            // -8192 to 8191, map to some parameter range
        }
    }
    
    fun updateMidiMapping(newState: MidiMappingState) {
        midiMappingState = newState
    }
    
    fun initMidi() {
        val devices = midiController.getAvailableDevices()
        if (devices.isNotEmpty()) {
            Logger.info { "Available MIDI devices: $devices" }
            // Auto-connect to first device, then start listening
            if (midiController.openDevice(devices.first())) {
                midiController.start(midiEventListener)
                Logger.info { "MIDI initialized and listening on: ${devices.first()}" }
            }
        } else {
            Logger.info { "No MIDI devices found" }
        }
    }
    
    fun stopMidi() {
        midiController.stop()
        midiController.closeDevice()
    }

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
        // Initialize all voice tunings to match UI state
        voiceStates.forEachIndexed { index, state ->
            engine.setVoiceTune(index, state.tune)
        }
        // Initialize other default parameters
        engine.setMasterVolume(masterVolume)
        engine.setDrive(drive)
        engine.setDistortionMix(distortionMix)
        engine.setDelayFeedback(delayFeedback)
        engine.setDelayMix(delayMix)
    }
    
    fun stopAudio() {
        engine.stop()
    }
}
