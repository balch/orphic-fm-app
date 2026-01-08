package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.plugins.DspBenderPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspDelayPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspDistortionPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspHyperLfoPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspPerStringBenderPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspStereoPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspVibratoPlugin
import kotlin.math.pow

/**
 * Shared implementation of SynthEngine using DSP primitive interfaces.
 * All audio routing logic is platform-independent.
 * 
 * Uses a plugin architecture where processing modules are injected and wired together.
 */
class DspSynthEngine(
    private val audioEngine: AudioEngine,
    plugins: Set<DspPlugin>,
) : SynthEngine {

    private val log = logging("DspSynthEngine")

    // Extract plugins by type
    private val hyperLfo = plugins.filterIsInstance<DspHyperLfoPlugin>().first()
    private val delayPlugin = plugins.filterIsInstance<DspDelayPlugin>().first()
    private val distortionPlugin = plugins.filterIsInstance<DspDistortionPlugin>().first()
    private val stereoPlugin = plugins.filterIsInstance<DspStereoPlugin>().first()
    private val vibratoPlugin = plugins.filterIsInstance<DspVibratoPlugin>().first()
    private val benderPlugin = plugins.filterIsInstance<DspBenderPlugin>().first()
    private val perStringBenderPlugin = plugins.filterIsInstance<DspPerStringBenderPlugin>().first()

    // 8 Voices with pitch ranges (0.5=bass, 1.0=mid, 2.0=high)
    private val voices = listOf(
        DspVoice(audioEngine, pitchMultiplier = 0.5),
        DspVoice(audioEngine, pitchMultiplier = 0.5),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 2.0),
        DspVoice(audioEngine, pitchMultiplier = 2.0),
        // REPL Voices (Quad 3)
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0)
    )

    // TOTAL FB: Output → LFO Frequency Modulation
    private val totalFbGain = audioEngine.createMultiply()

    // State caches
    private val quadPitchOffsets = DoubleArray(3) { 0.5 }
    private val voiceTuneCache = DoubleArray(12) { 0.5 }

    // Reactive monitoring flows
    private val _peakFlow = MutableStateFlow(0f)
    override val peakFlow: StateFlow<Float> = _peakFlow.asStateFlow()

    private val _cpuLoadFlow = MutableStateFlow(0f)
    override val cpuLoadFlow: StateFlow<Float> = _cpuLoadFlow.asStateFlow()

    private val _voiceLevelsFlow = MutableStateFlow(FloatArray(12))
    override val voiceLevelsFlow: StateFlow<FloatArray> = _voiceLevelsFlow.asStateFlow()

    private val _lfoOutputFlow = MutableStateFlow(0f)
    override val lfoOutputFlow: StateFlow<Float> = _lfoOutputFlow.asStateFlow()

    private val _masterLevelFlow = MutableStateFlow(0f)
    override val masterLevelFlow: StateFlow<Float> = _masterLevelFlow.asStateFlow()

    private val _driveFlow = MutableStateFlow(0f)
    override val driveFlow: StateFlow<Float> = _driveFlow.asStateFlow()

    private val _distortionMixFlow = MutableStateFlow(0f)
    override val distortionMixFlow: StateFlow<Float> = _distortionMixFlow.asStateFlow()

    private val _delayMixFlow = MutableStateFlow(0f)
    override val delayMixFlow: StateFlow<Float> = _delayMixFlow.asStateFlow()

    private val _delayFeedbackFlow = MutableStateFlow(0f)
    override val delayFeedbackFlow: StateFlow<Float> = _delayFeedbackFlow.asStateFlow()

    private val _quadPitchFlow = MutableStateFlow(FloatArray(3) { 0.5f })
    override val quadPitchFlow: StateFlow<FloatArray> = _quadPitchFlow.asStateFlow()

    private val _quadHoldFlow = MutableStateFlow(FloatArray(3))
    override val quadHoldFlow: StateFlow<FloatArray> = _quadHoldFlow.asStateFlow()

    private val _bendFlow = MutableStateFlow(0f)
    override val bendFlow: StateFlow<Float> = _bendFlow.asStateFlow()

    // ═══════════════════════════════════════════════════════════
    // Audio-Rate Automation System
    // ═══════════════════════════════════════════════════════════
    private data class AutomationSetup(
        val player: AutomationPlayer,
        val scaler: MultiplyAdd,
        val targets: List<AudioInput>,
        val restoreManualValue: () -> Unit
    )
    
    private val automationSetups = mutableMapOf<String, AutomationSetup>()
    private val activeAutomations = mutableSetOf<String>()

    // State Caches
    private val _voiceTune = FloatArray(12) { 0.5f }
    private val _voiceFmDepth = FloatArray(12)
    private val _voiceEnvelopeSpeed = FloatArray(12)
    private val _pairSharpness = FloatArray(6)
    private val _duoModSource = Array(6) { ModSource.OFF }
    private val _quadPitch = FloatArray(3) { 0.5f }
    private val _quadHold = FloatArray(3)
    private val _quadVolume = FloatArray(3) { 1.0f }  // Default to full volume
    private var _fmStructureCrossQuad = false
    private var _totalFeedback = 0.0f
    private var _voiceCoupling = 0.0f
    private var _stereoMode = StereoMode.VOICE_PAN

    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Register all plugin audio units
        plugins.forEach { plugin ->
            plugin.audioUnits.forEach { unit ->
                audioEngine.addUnit(unit)
            }
        }
        
        // Register local units
        audioEngine.addUnit(totalFbGain)
        
        // Initialize all plugins (sets up internal wiring)
        plugins.forEach { it.initialize() }

        // TOTAL FB: StereoPlugin.peak → scaled → HyperLfo.feedbackInput
        stereoPlugin.outputs["peakOutput"]?.connect(totalFbGain.inputA)
        totalFbGain.inputB.set(0.0) // Default: no feedback
        totalFbGain.output.connect(hyperLfo.feedbackInput)

        // ═══════════════════════════════════════════════════════════
        // INTER-PLUGIN WIRING
        // ═══════════════════════════════════════════════════════════

        // HyperLFO → Delay (modulation)
        hyperLfo.output.connect(delayPlugin.inputs["lfoInput"]!!)

        // Distortion outputs → Stereo sum
        distortionPlugin.outputs["outputLeft"]?.connect(stereoPlugin.inputs["dryInputLeft"]!!)
        distortionPlugin.outputs["outputRight"]?.connect(stereoPlugin.inputs["dryInputRight"]!!)

        // Delay wet outputs → Stereo sum
        delayPlugin.outputs["wetLeft"]?.connect(stereoPlugin.inputs["dryInputLeft"]!!)
        delayPlugin.outputs["wetRight"]?.connect(stereoPlugin.inputs["dryInputRight"]!!)
        delayPlugin.outputs["wet2Left"]?.connect(stereoPlugin.inputs["dryInputLeft"]!!)
        delayPlugin.outputs["wet2Right"]?.connect(stereoPlugin.inputs["dryInputRight"]!!)

        // Bender audio effects (tension/spring sounds) → Stereo sum (mono to both channels)
        benderPlugin.outputs["audioOutput"]?.connect(stereoPlugin.inputs["dryInputLeft"]!!)
        benderPlugin.outputs["audioOutput"]?.connect(stereoPlugin.inputs["dryInputRight"]!!)

        // Stereo outputs → LineOut
        stereoPlugin.outputs["lineOutLeft"]?.connect(audioEngine.lineOutLeft)
        stereoPlugin.outputs["lineOutRight"]?.connect(audioEngine.lineOutRight)

        // Wire voices to audio paths
        voices.forEachIndexed { index, voice ->
            // VOICES → DELAYS (wet path)
            voice.output.connect(delayPlugin.inputs["input"]!!)

            // VIBRATO → Voice frequency modulation
            vibratoPlugin.outputs["output"]?.connect(voice.vibratoInput)
            voice.vibratoDepth.set(1.0)

            // GLOBAL BENDER → Voice pitch bend modulation (for fader bender)
            benderPlugin.outputs["pitchOutput"]?.connect(voice.benderInput)
            
            // PER-STRING BENDER → Voice pitch bend modulation (for string bender)
            // Only wire first 8 voices (quad 0 and 1 = 4 strings * 2 voices)
            if (index < 8) {
                perStringBenderPlugin.outputs["voiceBend$index"]?.connect(voice.benderInput)
            }

            // COUPLING default depth
            voice.couplingDepth.set(0.0)
        }
        
        // Per-String Bender audio effects (tension/spring sounds) → Stereo sum
        perStringBenderPlugin.outputs["audioOutput"]?.connect(stereoPlugin.inputs["dryInputLeft"]!!)
        perStringBenderPlugin.outputs["audioOutput"]?.connect(stereoPlugin.inputs["dryInputRight"]!!)

        // Wire voice coupling
        for (pairIndex in 0 until 6) {
            val voiceA = voices[pairIndex * 2]
            val voiceB = voices[pairIndex * 2 + 1]
            voiceA.envelopeOutput.connect(voiceB.couplingInput)
            voiceB.envelopeOutput.connect(voiceA.couplingInput)
        }

        // Wire per-voice panning: Voice → PanL/R → Distortion inputs
        voices.forEachIndexed { index, voice ->
            // Voice audio goes to pan gain inputs
            voice.output.connect(stereoPlugin.getVoicePanInputLeft(index))
            voice.output.connect(stereoPlugin.getVoicePanInputRight(index))
            
            // Panned audio goes to distortion (DRY path)
            stereoPlugin.getVoicePanOutputLeft(index).connect(distortionPlugin.inputs["inputLeft"]!!)
            stereoPlugin.getVoicePanOutputRight(index).connect(distortionPlugin.inputs["inputRight"]!!)
        }

        // ═══════════════════════════════════════════════════════════
        // Automation Setup
        // ═══════════════════════════════════════════════════════════
        
        fun setupAutomation(
            id: String,
            targets: List<AudioInput>,
            scale: Double,
            offset: Double,
            restoreManualValue: () -> Unit
        ) {
            val player = audioEngine.createAutomationPlayer()
            val scaler = audioEngine.createMultiplyAdd()
            scaler.inputB.set(scale)
            scaler.inputC.set(offset)
            player.output.connect(scaler.inputA)
            
            automationSetups[id] = AutomationSetup(player, scaler, targets, restoreManualValue)
            audioEngine.addUnit(player)
            audioEngine.addUnit(scaler)
        }

        // LFO Frequencies
        setupAutomation("hyper_lfo_a", listOf(hyperLfo.frequencyA), 10.0, 0.01) { setHyperLfoFreq(0, getHyperLfoFreq(0)) }
        setupAutomation("hyper_lfo_b", listOf(hyperLfo.frequencyB), 10.0, 0.01) { setHyperLfoFreq(1, getHyperLfoFreq(1)) }

        // Delay Times
        setupAutomation("delay_time_1", listOf(delayPlugin.delay1TimeRampInput), 1.99, 0.01) { setDelayTime(0, getDelayTime(0)) }
        setupAutomation("delay_time_2", listOf(delayPlugin.delay2TimeRampInput), 1.99, 0.01) { setDelayTime(1, getDelayTime(1)) }

        // Delay Mod Depths
        setupAutomation("delay_mod_1", listOf(delayPlugin.delay1ModDepthRampInput), 0.1, 0.0) { setDelayModDepth(0, getDelayModDepth(0)) }
        setupAutomation("delay_mod_2", listOf(delayPlugin.delay2ModDepthRampInput), 0.1, 0.0) { setDelayModDepth(1, getDelayModDepth(1)) }

        // Delay Feedback
        setupAutomation("delay_feedback", listOf(delayPlugin.delay1FeedbackInput, delayPlugin.delay2FeedbackInput), 0.95, 0.0) { setDelayFeedback(getDelayFeedback()) }

        // Vibrato
        setupAutomation("vibrato", listOf(), 20.0, 0.0) { setVibrato(getVibrato()) }

        // Master Volume
        setupAutomation("master_volume", listOf(stereoPlugin.masterGainLeftInput, stereoPlugin.masterGainRightInput), 1.0, 0.0) { setMasterVolume(getMasterVolume()) }

        // Drive
        setupAutomation("drive", listOf(distortionPlugin.limiterLeftDrive, distortionPlugin.limiterRightDrive), 14.0, 1.0) { setDrive(getDrive()) }

        // Delay Mix - complex with wet and dry scalers
        run {
            val player = audioEngine.createAutomationPlayer()
            val wetScaler = audioEngine.createMultiplyAdd()
            val dryScaler = audioEngine.createMultiplyAdd()
            
            wetScaler.inputB.set(1.0)
            wetScaler.inputC.set(0.0)
            dryScaler.inputB.set(-1.0)
            dryScaler.inputC.set(1.0)
            
            player.output.connect(wetScaler.inputA)
            player.output.connect(dryScaler.inputA)
            
            val wetTargets = listOf(
                delayPlugin.delay1WetLeftGain, delayPlugin.delay1WetRightGain,
                delayPlugin.delay2WetLeftGain, delayPlugin.delay2WetRightGain
            )
            val dryTargets = listOf(distortionPlugin.dryGainLeftInput, distortionPlugin.dryGainRightInput)
            
            automationSetups["delay_mix"] = AutomationSetup(player, wetScaler, wetTargets + dryTargets) { setDelayMix(getDelayMix()) }
            automationSetups["delay_mix_dry"] = AutomationSetup(player, dryScaler, dryTargets) {}
            
            audioEngine.addUnit(player)
            audioEngine.addUnit(wetScaler)
            audioEngine.addUnit(dryScaler)
        }

        // Distortion Mix
        run {
            val player = audioEngine.createAutomationPlayer()
            val distScaler = audioEngine.createMultiplyAdd()
            val cleanScaler = audioEngine.createMultiplyAdd()
            
            distScaler.inputB.set(1.0)
            distScaler.inputC.set(0.0)
            cleanScaler.inputB.set(-1.0)
            cleanScaler.inputC.set(1.0)
            
            player.output.connect(distScaler.inputA)
            player.output.connect(cleanScaler.inputA)
            
            val distTargets = listOf(distortionPlugin.distortedPathLeftGain, distortionPlugin.distortedPathRightGain)
            val cleanTargets = listOf(distortionPlugin.cleanPathLeftGain, distortionPlugin.cleanPathRightGain)
            
            automationSetups["distortion_mix"] = AutomationSetup(player, distScaler, distTargets + cleanTargets) { setDistortionMix(getDistortionMix()) }
            automationSetups["distortion_mix_clean"] = AutomationSetup(player, cleanScaler, cleanTargets) {}
            
            audioEngine.addUnit(player)
            audioEngine.addUnit(distScaler)
            audioEngine.addUnit(cleanScaler)
        }

        // Set defaults
        setDelayMix(0.5f)

        // Voice Automation
        for (i in 0 until 12) {
            // Gate automation (0.0 to 1.0)
            setupAutomation(
                "voice_gate_$i",
                listOf(voices[i].gate),
                1.0,
                0.0
            ) { setVoiceGate(i, false) }

            // Frequency automation (Hz) - uses directFrequency to bypass pitchScaler
            // This ensures Notes play at exact Hz without pitch multiplier interference
            setupAutomation(
                "voice_freq_$i",
                listOf(voices[i].directFrequency),  // Bypass pitchScaler!
                1.0,
                0.0
            ) {
                // Restore: clear direct frequency and let pitchScaler take over again
                voices[i].directFrequency.disconnectAll()
                voices[i].directFrequency.set(0.0)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SynthEngine Implementation
    // ═══════════════════════════════════════════════════════════

    private var monitoringJob: Job? = null

    override fun start() {
        if (audioEngine.isRunning) return
        log.debug { "Starting Shared Audio Engine..." }
        audioEngine.start()

        monitoringJob = monitoringScope.launch {
            val voiceLevels = FloatArray(12)
            while (isActive) {
                val currentPeak = stereoPlugin.getPeak()
                _peakFlow.value = currentPeak
                _cpuLoadFlow.value = audioEngine.getCpuLoad()

                var voiceSum = 0f
                for (i in 0 until 12) {
                    val level = voices[i].getCurrentLevel()
                    voiceLevels[i] = level
                    voiceSum += level
                }
                _voiceLevelsFlow.value = voiceLevels.copyOf()
                _lfoOutputFlow.value = hyperLfo.getCurrentValue()
                
                val computedMaster = (voiceSum / 12f).coerceIn(0f, 1f)
                _masterLevelFlow.value = maxOf(currentPeak.coerceIn(0f, 1f), computedMaster)

                delay(33) // ~30fps
            }
        }
        log.debug { "Audio Engine Started" }
    }

    override fun stop() {
        log.debug { "Stopping Audio Engine..." }
        monitoringJob?.cancel()
        monitoringJob = null
        audioEngine.stop()
        log.debug { "Audio Engine Stopped" }
    }

    // Delay delegations
    override fun setDelayTime(index: Int, time: Float) = delayPlugin.setTime(index, time)
    override fun setDelayFeedback(amount: Float) {
        delayPlugin.setFeedback(amount)
        _delayFeedbackFlow.value = amount
    }
    override fun setDelayMix(amount: Float) {
        delayPlugin.setMix(amount)
        distortionPlugin.setDryLevel(1.0f - amount)
        _delayMixFlow.value = amount
    }
    override fun setDelayModDepth(index: Int, amount: Float) = delayPlugin.setModDepth(index, amount)
    override fun setDelayModSource(index: Int, isLfo: Boolean) = delayPlugin.setModSource(index, isLfo)
    override fun setDelayLfoWaveform(isTriangle: Boolean) = hyperLfo.setTriangleMode(isTriangle)
    @Deprecated("Use granular setDelayTime/Feedback instead")
    override fun setDelay(time: Float, feedback: Float) {
        setDelayTime(0, time)
        setDelayTime(1, time)
        setDelayFeedback(feedback)
    }
    override fun getDelayTime(index: Int): Float = delayPlugin.getTime(index)
    override fun getDelayFeedback(): Float = delayPlugin.getFeedback()
    override fun getDelayMix(): Float = delayPlugin.getMix()
    override fun getDelayModDepth(index: Int): Float = delayPlugin.getModDepth(index)
    override fun getDelayModSourceIsLfo(index: Int): Boolean = delayPlugin.getModSourceIsLfo(index)
    override fun getDelayLfoWaveformIsTriangle(): Boolean = true // Default to triangle

    // HyperLFO delegations
    override fun setHyperLfoFreq(index: Int, frequency: Float) = hyperLfo.setFreq(index, frequency)
    override fun setHyperLfoMode(mode: Int) = hyperLfo.setMode(mode)
    override fun setHyperLfoLink(active: Boolean) = hyperLfo.setLink(active)
    override fun getHyperLfoFreq(index: Int): Float = hyperLfo.getFreq(index)
    override fun getHyperLfoMode(): Int = hyperLfo.getMode()
    override fun getHyperLfoLink(): Boolean = hyperLfo.getLink()

    // Distortion delegations
    override fun setDrive(amount: Float) {
        distortionPlugin.setDrive(amount)
        _driveFlow.value = amount
    }
    override fun setDistortionMix(amount: Float) {
        distortionPlugin.setMix(amount)
        _distortionMixFlow.value = amount
    }
    override fun getDrive(): Float = distortionPlugin.getDrive()
    override fun getDistortionMix(): Float = distortionPlugin.getMix()

    // Stereo delegations
    override fun setMasterVolume(amount: Float) = stereoPlugin.setMasterVolume(amount)
    override fun getMasterVolume(): Float = stereoPlugin.getMasterVolume()
    override fun setVoicePan(index: Int, pan: Float) = stereoPlugin.setVoicePan(index, pan)
    override fun getVoicePan(index: Int): Float = stereoPlugin.getVoicePan(index)
    override fun setMasterPan(pan: Float) = stereoPlugin.setMasterPan(pan)
    override fun getMasterPan(): Float = stereoPlugin.getMasterPan()
    override fun setStereoMode(mode: StereoMode) {
        _stereoMode = mode
        delayPlugin.setStereoMode(mode == StereoMode.STEREO_DELAYS)
    }
    override fun getStereoMode(): StereoMode = _stereoMode

    // Vibrato delegation
    override fun setVibrato(amount: Float) = vibratoPlugin.setDepth(amount)
    override fun getVibrato(): Float = vibratoPlugin.getDepth()
    
    // Bender delegation
    override fun setBend(amount: Float) {
        benderPlugin.setBend(amount)
        _bendFlow.value = amount
    }
    override fun getBend(): Float = benderPlugin.getBend()
    
    // Per-String Bender delegation
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {
        if (perStringBenderPlugin.setStringBend(stringIndex, bendAmount, voiceMix)) {
            // Plugin requests voice trigger
            val voiceA = stringIndex * 2
            val voiceB = stringIndex * 2 + 1
            // Ensure we don't go out of bounds (though strings 0-3 map to voices 0-7 so its safe)
            if (voiceA < 12) setVoiceGate(voiceA, true)
            if (voiceB < 12) setVoiceGate(voiceB, true)
        }
    }
    
    override fun releaseStringBend(stringIndex: Int): Int {
        val (springDuration, shouldRelease) = perStringBenderPlugin.releaseString(stringIndex)
        
        if (shouldRelease) {
             val voiceA = stringIndex * 2
             val voiceB = stringIndex * 2 + 1
             if (voiceA < 12) setVoiceGate(voiceA, false)
             if (voiceB < 12) setVoiceGate(voiceB, false)
        }
        
        return springDuration
    }
    
    // Slide Bar delegation
    override fun setSlideBar(yPosition: Float, xPosition: Float) {
        perStringBenderPlugin.setSlideBar(yPosition, xPosition)
    }
    
    override fun releaseSlideBar() {
        perStringBenderPlugin.releaseSlideBar()
    }
    
    override fun resetStringBenders() {
        perStringBenderPlugin.resetAll()
    }

    // Voice controls (still managed locally)
    override fun setVoiceTune(index: Int, tune: Float) {
        _voiceTune[index] = tune
        voiceTuneCache[index] = tune.toDouble()
        updateVoiceFrequency(index)
    }

    private fun updateVoiceFrequency(index: Int) {
        val tune = voiceTuneCache[index]
        val quadIndex = index / 4
        val quadPitch = quadPitchOffsets[quadIndex]
        val baseFreq = 55.0 * 2.0.pow(tune * 4.0)
        val pitchMultiplier = 2.0.pow((quadPitch - 0.5) * 2.0)
        val finalFreq = baseFreq * pitchMultiplier
        voices[index].frequency.set(finalFreq)
        
        // Update string pluck frequency if this is the primary voice (A) of a pair
        // This ensures the string "pluck" sound matches the user's tuned notes (Issue 2)
        if (index % 2 == 0) {
            val stringIndex = index / 2
            perStringBenderPlugin.setStringFrequency(stringIndex, finalFreq)
        }
    }

    override fun setVoiceGate(index: Int, active: Boolean) {
        voices[index].gate.set(if (active) 1.0 else 0.0)
    }

    override fun setVoiceFeedback(index: Int, amount: Float) { /* Not implemented */ }

    override fun setVoiceFmDepth(index: Int, amount: Float) {
        _voiceFmDepth[index] = amount
        voices[index].fmDepth.set(amount.toDouble())
    }

    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {
        _voiceEnvelopeSpeed[index] = speed
        voices[index].setEnvelopeSpeed(speed)
    }

    override fun setPairSharpness(pairIndex: Int, sharpness: Float) {
        _pairSharpness[pairIndex] = sharpness
        val voiceA = pairIndex * 2
        val voiceB = voiceA + 1
        voices[voiceA].sharpness.set(sharpness.toDouble())
        voices[voiceB].sharpness.set(sharpness.toDouble())
    }

    override fun setQuadPitch(quadIndex: Int, pitch: Float) {
        _quadPitch[quadIndex] = pitch
        _quadPitchFlow.value = _quadPitch.copyOf() // shallow copy is fine for primitive array
        quadPitchOffsets[quadIndex] = pitch.toDouble()
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            updateVoiceFrequency(i)
        }
    }

    override fun setQuadHold(quadIndex: Int, amount: Float) {
        _quadHold[quadIndex] = amount
        _quadHoldFlow.value = _quadHold.copyOf()
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].setHoldLevel(amount.toDouble())
        }
    }

    override fun setQuadVolume(quadIndex: Int, volume: Float) {
        _quadVolume[quadIndex] = volume
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].setVolume(volume.toDouble())
        }
    }

    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) {
        _quadVolume[quadIndex] = targetVolume
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].fadeVolume(targetVolume.toDouble(), durationSeconds.toDouble())
        }
    }

    override fun setVoiceHold(index: Int, amount: Float) {
        voices[index].setHoldLevel(amount.toDouble())
    }

    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) {
        // Apply wobble as a real-time VCA modulation
        // wobbleOffset is in [-1, 1], range determines max modulation depth
        // At wobbleOffset=0: multiplier=1.0 (no change)
        // At wobbleOffset=1 with range=0.3: multiplier=1.3 (30% boost)
        // At wobbleOffset=-1 with range=0.3: multiplier=0.7 (30% cut)
        val multiplier = 1.0 + (wobbleOffset * range)
        voices[index].setWobbleMultiplier(multiplier.coerceIn(0.0, 2.0))
    }

    override fun setDuoModSource(duoIndex: Int, source: ModSource) {
        _duoModSource[duoIndex] = source
        val voiceA = duoIndex * 2
        val voiceB = voiceA + 1

        voices[voiceA].modInput.disconnectAll()
        voices[voiceB].modInput.disconnectAll()

        when (source) {
            ModSource.OFF -> { }
            ModSource.LFO -> {
                hyperLfo.output.connect(voices[voiceA].modInput)
                hyperLfo.output.connect(voices[voiceB].modInput)
            }
            ModSource.VOICE_FM -> {
                if (_fmStructureCrossQuad) {
                    when (duoIndex) {
                        0 -> {
                            voices[6].output.connect(voices[voiceA].modInput)
                            voices[7].output.connect(voices[voiceB].modInput)
                        }
                        1 -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                        2 -> {
                            voices[2].output.connect(voices[voiceA].modInput)
                            voices[3].output.connect(voices[voiceB].modInput)
                        }
                        3 -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                        // Quad 3 (pairs 4, 5) default to simple peer connection
                        else -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                    }
                } else {
                    voices[voiceA].output.connect(voices[voiceB].modInput)
                    voices[voiceB].output.connect(voices[voiceA].modInput)
                }
            }
        }
    }

    override fun setFmStructure(crossQuad: Boolean) {
        _fmStructureCrossQuad = crossQuad
    }

    override fun setTotalFeedback(amount: Float) {
        _totalFeedback = amount
        val scaledAmount = amount * 20.0
        totalFbGain.inputB.set(scaledAmount)
    }

    override fun setVoiceCoupling(amount: Float) {
        _voiceCoupling = amount
        val depthHz = amount * 30.0
        voices.forEach { voice ->
            voice.couplingDepth.set(depthHz)
        }
    }

    // Test tone
    private var testOsc: SineOscillator? = null
    private var testGain: Multiply? = null
    
    override fun playTestTone(frequency: Float) {
        log.debug { "Playing test tone at ${frequency}Hz" }
        if (!audioEngine.isRunning) {
            audioEngine.start()
        }
        
        if (testOsc == null) {
            testOsc = audioEngine.createSineOscillator()
            testGain = audioEngine.createMultiply()
            audioEngine.addUnit(testOsc!!)
            audioEngine.addUnit(testGain!!)
            testOsc!!.output.connect(testGain!!.inputA)
            testGain!!.output.connect(audioEngine.lineOutLeft)
            testGain!!.output.connect(audioEngine.lineOutRight)
        }
        
        testOsc!!.frequency.set(frequency.toDouble())
        testOsc!!.amplitude.set(1.0)
        testGain!!.inputB.set(0.3)
    }

    override fun stopTestTone() {
        testGain?.inputB?.set(0.0)
    }

    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) {
        val setup = automationSetups[controlId]
        if (setup == null) {
            log.debug { "Automation NOT FOUND: $controlId" }
            return
        }
        if (controlId.startsWith("voice_freq")) {
            log.debug { "Automation: $controlId, first value=${values.firstOrNull()} Hz, targets=${setup.targets.size}" }
        }
        
        val secondarySetup = when (controlId) {
            "delay_mix" -> automationSetups["delay_mix_dry"]
            "distortion_mix" -> automationSetups["distortion_mix_clean"]
            else -> null
        }
        
        // Prepare voice automation by zeroing out the manual value
        // This ensures the automation adds to 0, rather than the manual setting.
        if (controlId.startsWith("voice_freq_")) {
            val index = controlId.removePrefix("voice_freq_").toIntOrNull()
            if (index != null && index in 0..11) {
                voices[index].frequency.set(0.0)
            }
        } else if (controlId.startsWith("voice_gate_")) {
            val index = controlId.removePrefix("voice_gate_").toIntOrNull()
            if (index != null && index in 0..11) {
                voices[index].gate.set(0.0)
            }
        }
        
        setup.targets.forEach { it.disconnectAll() }
        
        when (controlId) {
            "delay_mix" -> {
                listOf(delayPlugin.delay1WetLeftGain, delayPlugin.delay1WetRightGain, 
                       delayPlugin.delay2WetLeftGain, delayPlugin.delay2WetRightGain).forEach {
                    setup.scaler.output.connect(it)
                }
                secondarySetup?.let { secondary ->
                    listOf(distortionPlugin.dryGainLeftInput, distortionPlugin.dryGainRightInput).forEach {
                        secondary.scaler.output.connect(it)
                    }
                }
            }
            "distortion_mix" -> {
                listOf(distortionPlugin.distortedPathLeftGain, distortionPlugin.distortedPathRightGain).forEach {
                    setup.scaler.output.connect(it)
                }
                secondarySetup?.let { secondary ->
                    listOf(distortionPlugin.cleanPathLeftGain, distortionPlugin.cleanPathRightGain).forEach {
                        secondary.scaler.output.connect(it)
                    }
                }
            }
            else -> setup.targets.forEach { setup.scaler.output.connect(it) }
        }
        
        setup.player.setPath(times, values, count)
        setup.player.setDuration(duration)
        setup.player.setMode(mode)
        setup.player.play()
        activeAutomations.add(controlId)
    }

    override fun clearParameterAutomation(controlId: String) {
        val setup = automationSetups[controlId] ?: return
        setup.player.stop()
        setup.targets.forEach { it.disconnectAll() }
        
        // Disconnect secondary targets (mix/dry/clean)
        when (controlId) {
            "delay_mix" -> automationSetups["delay_mix_dry"]?.targets?.forEach { it.disconnectAll() }
            "distortion_mix" -> automationSetups["distortion_mix_clean"]?.targets?.forEach { it.disconnectAll() }
            else -> {}
        }
        
        setup.restoreManualValue()
        activeAutomations.remove(controlId)
    }

    override fun getPeak(): Float = stereoPlugin.getPeak()
    override fun getCpuLoad(): Float = audioEngine.getCpuLoad()

    // Getters for State Saving
    override fun getVoiceTune(index: Int): Float = _voiceTune[index]
    override fun getVoiceFmDepth(index: Int): Float = _voiceFmDepth[index]
    override fun getVoiceEnvelopeSpeed(index: Int): Float = _voiceEnvelopeSpeed[index]
    override fun getPairSharpness(pairIndex: Int): Float = _pairSharpness[pairIndex]
    override fun getDuoModSource(duoIndex: Int): ModSource = _duoModSource[duoIndex]
    override fun getQuadPitch(quadIndex: Int): Float = _quadPitch[quadIndex]
    override fun getQuadHold(quadIndex: Int): Float = _quadHold[quadIndex]
    override fun getQuadVolume(quadIndex: Int): Float = _quadVolume[quadIndex]
    override fun getFmStructureCrossQuad(): Boolean = _fmStructureCrossQuad
    override fun getTotalFeedback(): Float = _totalFeedback
    override fun getVoiceCoupling(): Float = _voiceCoupling
}
