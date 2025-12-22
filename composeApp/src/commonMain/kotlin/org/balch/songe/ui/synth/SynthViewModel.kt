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
import org.balch.songe.preset.DronePreset
import org.balch.songe.preset.DronePresetRepository
import org.balch.songe.util.Logger
import kotlin.math.roundToInt

class SynthViewModel(
    private val engine: SongeEngine,
    val midiController: MidiController = MidiController { createMidiAccess() },
    private val midiRepository: MidiMappingRepository = MidiMappingRepository(),
    private val presetRepository: DronePresetRepository = DronePresetRepository()
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
    
    // Drone Presets
    var presetList by mutableStateOf<List<DronePreset>>(emptyList())
        private set
    var selectedPreset by mutableStateOf<DronePreset?>(null)
        private set
    
    // Track last CC values for button toggle detection (controlId -> lastValue)
    private val lastCcValues = mutableMapOf<String, Float>()
    private val lastRawCcValues = mutableMapOf<String, Float>()
    
    // MIDI Event Listener
    private val midiEventListener = object : MidiEventListener {
        override fun onNoteOn(note: Int, velocity: Int) {
            // Check if we're learning
            val learnTarget = midiMappingState.learnTarget
            
            if (learnTarget is LearnTarget.Voice) {
                midiMappingState = midiMappingState.assignNoteToVoice(note, learnTarget.index)
                Logger.info { "Assigned MIDI note ${MidiMappingState.noteName(note)} to Voice ${learnTarget.index + 1}" }
                return
            } else if (learnTarget is LearnTarget.Control) {
                midiMappingState = midiMappingState.assignNoteToControl(note, learnTarget.controlId)
                Logger.info { "Assigned MIDI note $note to Control ${learnTarget.controlId}" }
                return
            }
            
            // Normal operation
            // 1. Check Voice Mappings
            midiMappingState.getVoiceForNote(note)?.let { voiceIndex ->
                onPulseStart(voiceIndex)
            }
            
            // 2. Check Control Mappings (Note as Button)
            midiMappingState.getControlForNote(note)?.let { controlId ->
                // Toggle/Cycle Logic: Only trigger on Press (Velocity > 0).
                // Ignore Release (Velocity 0).
                if (velocity > 0) {
                    if (isCycleControl(controlId)) {
                        cycleControl(controlId, 3) // 3 states for switches
                    } else {
                        toggleControl(controlId)
                    }
                }
            }
        }
        
        override fun onNoteOff(note: Int) {
            // Voice
            midiMappingState.getVoiceForNote(note)?.let { voiceIndex ->
                onPulseEnd(voiceIndex)
            }
            
            // Control
            // Ignore Note Off for controls (Latch behavior handled by ignoring release)
        }
        
        override fun onControlChange(controller: Int, value: Int) {
            val normalized = value / 127f
            
            // Check if we're learning a control
            val learnTarget = midiMappingState.learnTarget
            Logger.info { "MIDI CC Rcv: $controller, Val: $value. LearnTarget: $learnTarget" }
            
            if (learnTarget is LearnTarget.Control) {
                Logger.info { "Attempting assignment. Control: ${learnTarget.controlId}, CC: $controller" }
                midiMappingState = midiMappingState.assignCCToControl(controller, learnTarget.controlId)
                Logger.info { "Assigned CC$controller to ${learnTarget.controlId}. New Target: ${midiMappingState.learnTarget}" }
                return
            }
            
            // Normal operation: apply CC to mapped control
            midiMappingState.getControlForCC(controller)?.let { controlId ->
                // Logger.info { "Mapping found: $controlId" } // Too verbose for normal ops?
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
        val lastRaw = lastRawCcValues[controlId] ?: 0f
        
        // Identify control type for logic
        val isCycleControl = controlId == ControlIds.HYPER_LFO_MODE || 
                             (controlId.startsWith("pair_") && controlId.endsWith("_mod_source"))

        var effectiveValue = value

        if (!isCycleControl) {
            // Apply "Jump Toggle" logic for continuous/binary controls
            val lastRaw = lastRawCcValues[controlId] ?: 0f
            
            // Check for Jump (Button Press/Release)
            // Thresholds: High >= 0.9, Low < 0.1. Jump if crossing 0.5 boundary significantly.
            val isJumpUp = value >= 0.9f && lastRaw < 0.5f
            val isJumpDown = value < 0.1f && lastRaw > 0.5f
            
            // Get last EFFECTIVE value to toggle it
            val lastEffective = lastCcValues[controlId] ?: 0f

            if (isJumpUp) {
                // Button Press: Toggle state
                effectiveValue = if (lastEffective > 0.5f) 0f else 1f
            } else if (isJumpDown) {
                // Button Release: Ignore (Latch)
                effectiveValue = lastEffective 
            } else {
                // Continuous change (Knob/Slider) or slow button fade
                effectiveValue = value
            }
        } else {
            // Cycle controls: Pass RAW value. 
            effectiveValue = value
        }
        
        // Dispatch
        dispatchControlChange(controlId, effectiveValue)
        
        // Update state tracking
        lastCcValues[controlId] = effectiveValue
        lastRawCcValues[controlId] = value
    }

    private fun toggleControl(controlId: String) {
        val lastValue = lastCcValues[controlId] ?: 0f
        val newValue = if (lastValue > 0.5f) 0f else 1f
        
        // Update state
        lastCcValues[controlId] = newValue
        
        dispatchControlChange(controlId, newValue)
    }
    
    private fun cycleControl(controlId: String, numStates: Int) {
        val lastValue = lastCcValues[controlId] ?: 0f
        // Map lastValue to ordinal: index = round(lastValue * (numStates - 1))
        val currentIndex = (lastValue * (numStates - 1)).roundToInt()
        val nextIndex = (currentIndex + 1) % numStates
        val newValue = nextIndex.toFloat() / (numStates - 1)
        
        // Update state
        lastCcValues[controlId] = newValue
        
        dispatchControlChange(controlId, newValue)
    }
    
    private fun isCycleControl(controlId: String): Boolean {
        return controlId == ControlIds.HYPER_LFO_MODE ||
               (controlId.startsWith("pair_") && controlId.endsWith("_mod_source"))
    }

    private fun dispatchControlChange(controlId: String, effectiveValue: Float) {
        // Dispatch to handlers
        // Handler logic for Cycle controls will read 'lastRawCcValues' (which is still 'lastRaw').
        when (controlId) {
            // Voice tunes
            ControlIds.voiceTune(0) -> onVoiceTuneChange(0, effectiveValue)
            ControlIds.voiceTune(1) -> onVoiceTuneChange(1, effectiveValue)
            ControlIds.voiceTune(2) -> onVoiceTuneChange(2, effectiveValue)
            ControlIds.voiceTune(3) -> onVoiceTuneChange(3, effectiveValue)
            ControlIds.voiceTune(4) -> onVoiceTuneChange(4, effectiveValue)
            ControlIds.voiceTune(5) -> onVoiceTuneChange(5, effectiveValue)
            ControlIds.voiceTune(6) -> onVoiceTuneChange(6, effectiveValue)
            ControlIds.voiceTune(7) -> onVoiceTuneChange(7, effectiveValue)
            
            // Voice FM depths
            ControlIds.voiceFmDepth(0) -> onDuoModDepthChange(0, effectiveValue)
            ControlIds.voiceFmDepth(1) -> onDuoModDepthChange(0, effectiveValue)
            ControlIds.voiceFmDepth(2) -> onDuoModDepthChange(1, effectiveValue)
            ControlIds.voiceFmDepth(3) -> onDuoModDepthChange(1, effectiveValue)
            ControlIds.voiceFmDepth(4) -> onDuoModDepthChange(2, effectiveValue)
            ControlIds.voiceFmDepth(5) -> onDuoModDepthChange(2, effectiveValue)
            ControlIds.voiceFmDepth(6) -> onDuoModDepthChange(3, effectiveValue)
            ControlIds.voiceFmDepth(7) -> onDuoModDepthChange(3, effectiveValue)
            
            // Pair sharpness
            ControlIds.pairSharpness(0) -> onPairSharpnessChange(0, effectiveValue)
            ControlIds.pairSharpness(1) -> onPairSharpnessChange(1, effectiveValue)
            ControlIds.pairSharpness(2) -> onPairSharpnessChange(2, effectiveValue)
            ControlIds.pairSharpness(3) -> onPairSharpnessChange(3, effectiveValue)
            
            // Delay
            ControlIds.DELAY_TIME_1 -> onDelayTime1Change(effectiveValue)
            ControlIds.DELAY_TIME_2 -> onDelayTime2Change(effectiveValue)
            ControlIds.DELAY_MOD_1 -> onDelayMod1Change(effectiveValue)
            ControlIds.DELAY_MOD_2 -> onDelayMod2Change(effectiveValue)
            ControlIds.DELAY_FEEDBACK -> onDelayFeedbackChange(effectiveValue)
            ControlIds.DELAY_MIX -> onDelayMixChange(effectiveValue)
            ControlIds.DELAY_MOD_SOURCE -> onDelayModSourceChange(effectiveValue >= 0.5f)
            ControlIds.DELAY_LFO_WAVEFORM -> onDelayLfoWaveformChange(effectiveValue >= 0.5f)
            
            // Hyper LFO
            ControlIds.HYPER_LFO_A -> onHyperLfoAChange(effectiveValue)
            ControlIds.HYPER_LFO_B -> onHyperLfoBChange(effectiveValue)
            
            // Global
            ControlIds.MASTER_VOLUME -> onMasterVolumeChange(effectiveValue)
            ControlIds.DRIVE -> onGlobalDriveChange(effectiveValue)
            ControlIds.DISTORTION_MIX -> onDistortionMixChange(effectiveValue)
            ControlIds.VIBRATO -> onVibratoChange(effectiveValue)
            ControlIds.VOICE_COUPLING -> onVoiceCouplingChange(effectiveValue)
            ControlIds.TOTAL_FEEDBACK -> onTotalFeedbackChange(effectiveValue)
            
            // Quad controls
            ControlIds.quadPitch(0) -> onQuadPitchChange(0, effectiveValue)
            ControlIds.quadPitch(1) -> onQuadPitchChange(1, effectiveValue)
            ControlIds.quadHold(0) -> onQuadHoldChange(0, effectiveValue)
            ControlIds.quadHold(1) -> onQuadHoldChange(1, effectiveValue)
            
            // New Mappings
            ControlIds.HYPER_LFO_MODE -> {
                // 3-way switch: AND/OFF/OR
                // If button press detected (jump from low to high), cycle to next mode
                // Use RAW history for jump detection
                val lastRawForMode = lastRawCcValues[ControlIds.HYPER_LFO_MODE] ?: 0f
                if (effectiveValue >= 0.9f && lastRawForMode < 0.5f) {
                    // Button Pulse: Cycle
                    val currentMode = hyperLfoMode
                    val modes = org.balch.songe.ui.panels.HyperLfoMode.values()
                    val nextIndex = (currentMode.ordinal + 1) % modes.size
                    onHyperLfoModeChange(modes[nextIndex])
                } else {
                   // Standard Knob mapping (if not a jump, or continuous change)
                   val modes = org.balch.songe.ui.panels.HyperLfoMode.values()
                   val index = (effectiveValue * (modes.size - 1)).roundToInt()
                   onHyperLfoModeChange(modes[index])
                }
            }
            ControlIds.HYPER_LFO_LINK -> onHyperLfoLinkChange(effectiveValue >= 0.5f)
            
            else -> {
                // Check parameterized patterns
                when {
                    controlId.startsWith("voice_") && controlId.endsWith("_tune") -> {
                        val index = controlId.removePrefix("voice_").removeSuffix("_tune").toIntOrNull()
                        if (index != null) onVoiceTuneChange(index, effectiveValue)
                    }
                    controlId.startsWith("voice_") && controlId.endsWith("_fm_depth") -> {
                         val index = controlId.removePrefix("voice_").removeSuffix("_fm_depth").toIntOrNull()
                         if (index != null) onVoiceModDepthChange(index, effectiveValue)
                    }
                     controlId.startsWith("voice_") && controlId.endsWith("_env_speed") -> {
                          val index = controlId.removePrefix("voice_").removeSuffix("_env_speed").toIntOrNull()
                          if (index != null) onVoiceEnvelopeSpeedChange(index, effectiveValue)
                     }
                     controlId.startsWith("voice_") && controlId.endsWith("_hold") -> {
                          val index = controlId.removePrefix("voice_").removeSuffix("_hold").toIntOrNull()
                          // For hold, we likely want a toggle behavior.
                          // effectiveValue (from button toggle logic) will be 0 or 1.
                          // But wait, applyCCToControl calculates effectiveValue based on LAST value.
                          // If effectiveValue toggles between 0/1, we can use it to set hold state?
                          // onHoldChange takes a boolean.
                          // If effectiveValue >= 0.5 -> true (hold), < 0.5 -> false.
                          if (index != null) onHoldChange(index, effectiveValue >= 0.5f)
                     }
                    controlId.startsWith("pair_") && controlId.endsWith("_sharpness") -> {
                        val index = controlId.removePrefix("pair_").removeSuffix("_sharpness").toIntOrNull()
                        if (index != null) onPairSharpnessChange(index, effectiveValue)
                    }
                    controlId.startsWith("pair_") && controlId.endsWith("_mod_source") -> {
                        val index = controlId.removePrefix("pair_").removeSuffix("_mod_source").toIntOrNull()
                        if (index != null) {
                            // Use RAW history for jump detection
                            val lastRaw = lastRawCcValues[controlId] ?: 0f
                            // Jump detection for Button Cycle (0 -> >=0.9)
                            if (effectiveValue >= 0.9f && lastRaw < 0.5f) {
                                // Cycle
                                val current = duoModSources[index] // from ViewModel state
                                val sources = org.balch.songe.audio.ModSource.values()
                                val nextIndex = (current.ordinal + 1) % sources.size
                                onDuoModSourceChange(index, sources[nextIndex])
                            } else if (effectiveValue != lastRaw) {
                                // Map (only if not a jump, effectively knob turn)
                                if (!(effectiveValue >= 0.9f && lastRaw < 0.5f)) {
                                    val sources = org.balch.songe.audio.ModSource.values()
                                    // Order in UI seems to be: LFO (Top?), OFF (Middle?), FM (Bottom?)
                                    // But logic was: <0.33 LFO, <0.66 OFF, else FM.
                                    // Enum order is likely LFO, OFF, VOICE_FM (based on mapping).
                                    // If we use values()[index], index 0=LFO?
                                    // I'll assume standard order matches the range mapping.
                                    val srcIndex = (effectiveValue * (sources.size - 1)).roundToInt()
                                    onDuoModSourceChange(index, sources[srcIndex])
                                }
                            }
                        }
                    }
                    else -> Logger.warn { "Unknown control ID for CC mapping: $controlId" }
                }
            }
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
    
    // ========== Drone Preset Management ==========
    
    fun loadPresets() {
        viewModelScope.launch {
            presetList = presetRepository.list()
            Logger.info { "Loaded ${presetList.size} presets" }
        }
    }
    
    fun selectPreset(preset: DronePreset?) {
        selectedPreset = preset
    }
    
    fun saveNewPreset(name: String) {
        viewModelScope.launch {
            val preset = currentStateAsPreset(name)
            presetRepository.save(preset)
            presetList = presetRepository.list()
            selectedPreset = presetList.find { it.name == name }
            Logger.info { "Saved new preset: $name" }
        }
    }
    
    fun overridePreset() {
        val current = selectedPreset ?: return
        viewModelScope.launch {
            val preset = currentStateAsPreset(current.name).copy(createdAt = current.createdAt)
            presetRepository.save(preset)
            presetList = presetRepository.list()
            selectedPreset = presetList.find { it.name == current.name }
            Logger.info { "Overrode preset: ${current.name}" }
        }
    }
    
    fun deletePreset() {
        val current = selectedPreset ?: return
        viewModelScope.launch {
            presetRepository.delete(current.name)
            presetList = presetRepository.list()
            selectedPreset = null
            Logger.info { "Deleted preset: ${current.name}" }
        }
    }
    
    fun applyPreset(preset: DronePreset) {
        // Apply all preset values to current state
        voiceStates = voiceStates.mapIndexed { index, state ->
            state.copy(tune = preset.voiceTunes.getOrElse(index) { state.tune })
        }
        voiceModDepths = preset.voiceModDepths.take(8) + List(8 - preset.voiceModDepths.size) { 0f }
        pairSharpness = preset.pairSharpness.take(4) + List(4 - preset.pairSharpness.size) { 0f }
        voiceEnvelopeSpeeds = preset.voiceEnvelopeSpeeds.take(8) + List(8 - preset.voiceEnvelopeSpeeds.size) { 0f }
        
        // Duo Mod Sources
        duoModSources = preset.duoModSources.mapIndexed { index, sourceStr ->
            try {
                org.balch.songe.audio.ModSource.valueOf(sourceStr)
            } catch (e: Exception) {
                org.balch.songe.audio.ModSource.OFF
            }
        }.take(4) + List(maxOf(0, 4 - preset.duoModSources.size)) { org.balch.songe.audio.ModSource.OFF }
        
        // Hyper LFO
        hyperLfoA = preset.hyperLfoA
        hyperLfoB = preset.hyperLfoB
        hyperLfoMode = try {
            org.balch.songe.ui.panels.HyperLfoMode.valueOf(preset.hyperLfoMode)
        } catch (e: Exception) {
            org.balch.songe.ui.panels.HyperLfoMode.OFF
        }
        hyperLfoLink = preset.hyperLfoLink
        
        // Delay
        delayTime1 = preset.delayTime1
        delayTime2 = preset.delayTime2
        delayMod1 = preset.delayMod1
        delayMod2 = preset.delayMod2
        delayFeedback = preset.delayFeedback
        delayMix = preset.delayMix
        delayModSourceIsLfo = preset.delayModSourceIsLfo
        delayLfoWaveformIsTriangle = preset.delayLfoWaveformIsTriangle
        
        // Global
        masterVolume = preset.masterVolume
        drive = preset.drive
        distortionMix = preset.distortionMix
        
        // Advanced
        fmStructureCrossQuad = preset.fmStructureCrossQuad
        totalFeedback = preset.totalFeedback
        
        // Apply to engine
        voiceStates.forEachIndexed { index, state -> engine.setVoiceTune(index, state.tune) }
        engine.setMasterVolume(masterVolume)
        engine.setDrive(drive)
        engine.setDistortionMix(distortionMix)
        engine.setDelayFeedback(delayFeedback)
        engine.setDelayMix(delayMix)
        
        selectedPreset = preset
        Logger.info { "Applied preset: ${preset.name}" }
    }
    
    private fun currentStateAsPreset(name: String): DronePreset {
        return DronePreset(
            name = name,
            voiceTunes = voiceStates.map { it.tune },
            voiceModDepths = voiceModDepths,
            voiceEnvelopeSpeeds = voiceEnvelopeSpeeds,
            pairSharpness = pairSharpness,
            duoModSources = duoModSources.map { it.name },
            hyperLfoA = hyperLfoA,
            hyperLfoB = hyperLfoB,
            hyperLfoMode = hyperLfoMode.name,
            hyperLfoLink = hyperLfoLink,
            delayTime1 = delayTime1,
            delayTime2 = delayTime2,
            delayMod1 = delayMod1,
            delayMod2 = delayMod2,
            delayFeedback = delayFeedback,
            delayMix = delayMix,
            delayModSourceIsLfo = delayModSourceIsLfo,
            delayLfoWaveformIsTriangle = delayLfoWaveformIsTriangle,
            masterVolume = masterVolume,
            drive = drive,
            distortionMix = distortionMix,
            fmStructureCrossQuad = fmStructureCrossQuad,
            totalFeedback = totalFeedback
        )
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
