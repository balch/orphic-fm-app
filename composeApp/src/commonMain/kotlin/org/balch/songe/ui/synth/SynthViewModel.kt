package org.balch.songe.ui.synth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.songe.audio.SongeEngine
import org.balch.songe.audio.VoiceState
import org.balch.songe.input.LearnTarget
import org.balch.songe.input.MidiController
import org.balch.songe.input.MidiEventListener
import org.balch.songe.input.MidiMappingRepository
import org.balch.songe.input.MidiMappingState
import org.balch.songe.input.MidiMappingState.Companion.ControlIds
import org.balch.songe.input.createMidiAccess
import org.balch.songe.util.Logger

class SynthViewModel(
    private val engine: SongeEngine,
    val midiController: MidiController = MidiController { createMidiAccess() },
    private val midiRepository: MidiMappingRepository = MidiMappingRepository()
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
    
    // Voice Envelope Speeds (0=Fast, 1=Slow, continuous)
    var voiceEnvelopeSpeeds by mutableStateOf(List(8) { 0.0f }) // Default: Fast
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
    var hyperLfoMode by mutableStateOf(org.balch.songe.ui.panels.HyperLfoMode.OFF)
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
    
    // Learn mode state
    var isLearnModeActive by mutableStateOf(false)
        private set
    
    // Backup state for cancellation
    private var mappingBeforeLearn: MidiMappingState? = null
    
    // MIDI polling job
    private var midiPollingJob: Job? = null
    
    // Last known device name for reconnection
    private var lastDeviceName: String? = null
    
    // Observable MIDI connection state for UI updates
    var isMidiConnected by mutableStateOf(false)
        private set
    var connectedMidiDeviceName by mutableStateOf<String?>(null)
        private set
    
    // MIDI Event Listener
    private val midiEventListener = object : MidiEventListener {
        override fun onNoteOn(note: Int, velocity: Int) {
            // Check if we're learning a voice
            val learnTarget = midiMappingState.learnTarget
            if (learnTarget is LearnTarget.Voice) {
                midiMappingState = midiMappingState.assignNoteToVoice(note, learnTarget.index)
                Logger.info { "Assigned MIDI note ${MidiMappingState.noteName(note)} to Voice ${learnTarget.index + 1}" }
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
            val normalized = value / 127f
            
            // Check if we're learning a control
            val learnTarget = midiMappingState.learnTarget
            if (learnTarget is LearnTarget.Control) {
                midiMappingState = midiMappingState.assignCCToControl(controller, learnTarget.controlId)
                Logger.info { "Assigned CC$controller to ${learnTarget.controlId}" }
                return
            }
            
            // Normal operation: apply CC to mapped control
            midiMappingState.getControlForCC(controller)?.let { controlId ->
                applyCCToControl(controlId, normalized)
            }
            
            // Legacy: CC1 = Mod wheel → vibrato (if not mapped)
            if (controller == 1 && midiMappingState.getControlForCC(1) == null) {
                onVibratoChange(normalized)
            }
        }
        
        override fun onPitchBend(value: Int) {
            // -8192 to 8191, map to 0-1 range
            val normalized = (value + 8192) / 16383f
            // Could apply to quad pitch or other parameter
        }
    }
    
    // ═══════════════════════════════════════════════════════════
    // LEARN MODE
    // ═══════════════════════════════════════════════════════════
    
    fun toggleLearnMode() {
        if (isLearnModeActive) {
            // Exit learn mode without saving
            cancelLearnMode()
        } else {
            // Enter learn mode
            isLearnModeActive = true
            mappingBeforeLearn = midiMappingState
            Logger.info { "Entered MIDI Learn Mode" }
        }
    }
    
    fun saveLearnedMappings() {
        isLearnModeActive = false
        midiMappingState = midiMappingState.cancelLearn() // Clear any pending learn target
        mappingBeforeLearn = null
        
        // Persist to storage
        midiController.currentDeviceName?.let { deviceName ->
            viewModelScope.launch {
                midiRepository.save(deviceName, midiMappingState)
            }
        }
        Logger.info { "Saved MIDI mappings" }
    }
    
    fun cancelLearnMode() {
        isLearnModeActive = false
        mappingBeforeLearn?.let { midiMappingState = it }
        mappingBeforeLearn = null
        Logger.info { "Cancelled MIDI Learn Mode" }
    }
    
    /**
     * Select a control for MIDI CC learning.
     * Call this when a knob/control is clicked in learn mode.
     */
    fun selectControlForLearning(controlId: String) {
        if (isLearnModeActive) {
            midiMappingState = midiMappingState.startLearnControl(controlId)
            Logger.info { "Learning MIDI CC for: $controlId" }
        }
    }
    
    /**
     * Check if a control is currently being learned.
     */
    fun isControlBeingLearned(controlId: String): Boolean {
        return midiMappingState.isLearningControl(controlId)
    }
    
    /**
     * Select a voice for MIDI note learning.
     * Call this when a pulse button is clicked in learn mode.
     */
    fun selectVoiceForLearning(voiceIndex: Int) {
        if (isLearnModeActive) {
            midiMappingState = midiMappingState.startLearnVoice(voiceIndex)
            Logger.info { "Learning MIDI note for Voice ${voiceIndex + 1}" }
        }
    }
    
    /**
     * Check if a voice is currently being learned.
     */
    fun isVoiceBeingLearned(voiceIndex: Int): Boolean {
        return midiMappingState.isLearningVoice(voiceIndex)
    }
    
    private fun applyCCToControl(controlId: String, value: Float) {
        when (controlId) {
            // Voice tunes
            ControlIds.voiceTune(0) -> onVoiceTuneChange(0, value)
            ControlIds.voiceTune(1) -> onVoiceTuneChange(1, value)
            ControlIds.voiceTune(2) -> onVoiceTuneChange(2, value)
            ControlIds.voiceTune(3) -> onVoiceTuneChange(3, value)
            ControlIds.voiceTune(4) -> onVoiceTuneChange(4, value)
            ControlIds.voiceTune(5) -> onVoiceTuneChange(5, value)
            ControlIds.voiceTune(6) -> onVoiceTuneChange(6, value)
            ControlIds.voiceTune(7) -> onVoiceTuneChange(7, value)
            
            // Voice FM depths
            ControlIds.voiceFmDepth(0) -> onDuoModDepthChange(0, value)
            ControlIds.voiceFmDepth(1) -> onDuoModDepthChange(0, value)
            ControlIds.voiceFmDepth(2) -> onDuoModDepthChange(1, value)
            ControlIds.voiceFmDepth(3) -> onDuoModDepthChange(1, value)
            ControlIds.voiceFmDepth(4) -> onDuoModDepthChange(2, value)
            ControlIds.voiceFmDepth(5) -> onDuoModDepthChange(2, value)
            ControlIds.voiceFmDepth(6) -> onDuoModDepthChange(3, value)
            ControlIds.voiceFmDepth(7) -> onDuoModDepthChange(3, value)
            
            // Pair sharpness
            ControlIds.pairSharpness(0) -> onPairSharpnessChange(0, value)
            ControlIds.pairSharpness(1) -> onPairSharpnessChange(1, value)
            ControlIds.pairSharpness(2) -> onPairSharpnessChange(2, value)
            ControlIds.pairSharpness(3) -> onPairSharpnessChange(3, value)
            
            // Delay
            ControlIds.DELAY_TIME_1 -> onDelayTime1Change(value)
            ControlIds.DELAY_TIME_2 -> onDelayTime2Change(value)
            ControlIds.DELAY_MOD_1 -> onDelayMod1Change(value)
            ControlIds.DELAY_MOD_2 -> onDelayMod2Change(value)
            ControlIds.DELAY_FEEDBACK -> onDelayFeedbackChange(value)
            ControlIds.DELAY_MIX -> onDelayMixChange(value)
            
            // Hyper LFO
            ControlIds.HYPER_LFO_A -> onHyperLfoAChange(value)
            ControlIds.HYPER_LFO_B -> onHyperLfoBChange(value)
            
            // Global
            ControlIds.MASTER_VOLUME -> onMasterVolumeChange(value)
            ControlIds.DRIVE -> onGlobalDriveChange(value)
            ControlIds.DISTORTION_MIX -> onDistortionMixChange(value)
            ControlIds.VIBRATO -> onVibratoChange(value)
            ControlIds.VOICE_COUPLING -> onVoiceCouplingChange(value)
            ControlIds.TOTAL_FEEDBACK -> onTotalFeedbackChange(value)
            
            // Quad controls
            ControlIds.quadPitch(0) -> onQuadPitchChange(0, value)
            ControlIds.quadPitch(1) -> onQuadPitchChange(1, value)
            ControlIds.quadHold(0) -> onQuadHoldChange(0, value)
            ControlIds.quadHold(1) -> onQuadHoldChange(1, value)
            
            else -> Logger.warn { "Unknown control ID for CC mapping: $controlId" }
        }
    }
    
    fun initMidi() {
        tryConnectMidi()
        startMidiPolling()
    }
    
    private fun tryConnectMidi(): Boolean {
        val devices = midiController.getAvailableDevices()
        if (devices.isNotEmpty()) {
            Logger.info { "Available MIDI devices: $devices" }
            
            // Prefer last known device if available, otherwise use first device
            val deviceName = if (lastDeviceName != null && devices.contains(lastDeviceName)) {
                lastDeviceName!!
            } else {
                devices.first()
            }
            
            if (midiController.openDevice(deviceName)) {
                midiController.start(midiEventListener)
                lastDeviceName = deviceName
                
                // Update observable state for UI
                isMidiConnected = true
                connectedMidiDeviceName = deviceName
                
                Logger.info { "MIDI initialized and listening on: $deviceName" }
                
                // Load saved mappings for this device
                viewModelScope.launch {
                    midiRepository.load(deviceName)?.let { savedMapping ->
                        midiMappingState = savedMapping
                        Logger.info { "Loaded saved MIDI mappings for $deviceName" }
                    }
                }
                return true
            }
        } else {
            Logger.info { "No MIDI devices found" }
        }
        return false
    }
    
    private fun startMidiPolling() {
        midiPollingJob?.cancel()
        midiPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(2000) // Check every 2 seconds
                
                val wasOpen = midiController.isOpen
                val stillAvailable = midiController.isCurrentDeviceAvailable()
                
                if (wasOpen && !stillAvailable) {
                    // Device was disconnected
                    val name = midiController.currentDeviceName
                    Logger.info { "MIDI device disconnected: $name" }
                    midiController.closeDevice()
                    
                    // Update observable state for UI
                    isMidiConnected = false
                    connectedMidiDeviceName = null
                } else if (!wasOpen) {
                    // Try to reconnect
                    val devices = midiController.getAvailableDevices()
                    if (devices.isNotEmpty()) {
                        Logger.info { "MIDI device(s) available, attempting to connect..." }
                        tryConnectMidi()
                    }
                }
            }
        }
    }
    
    fun stopMidi() {
        midiPollingJob?.cancel()
        midiPollingJob = null
        midiController.stop()
        midiController.closeDevice()
        
        // Update observable state
        isMidiConnected = false
        connectedMidiDeviceName = null
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
    
    fun onVoiceEnvelopeSpeedChange(index: Int, speed: Float) {
        val newSpeeds = voiceEnvelopeSpeeds.toMutableList()
        newSpeeds[index] = speed
        voiceEnvelopeSpeeds = newSpeeds
        engine.setVoiceEnvelopeSpeed(index, speed)
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
        engine.setHyperLfoMode(mode.ordinal) // 0=AND, 1=OFF, 2=OR
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
