package org.balch.orpheus.plugins.perstringbender

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioPort
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import kotlin.math.absoluteValue
import kotlin.math.pow

/**
 * DSP Plugin for per-string pitch bending.
 * 
 * Each of the 4 strings controls bending for its 2 associated voices (duo).
 * Features:
 * - Per-string pitch bend based on horizontal deflection
 * - BIDIRECTIONAL: Left strings (0,1) and Right strings (2,3) bend in opposite directions
 *   - Pulling OUTWARD = pitch UP, Pulling INWARD = pitch DOWN
 *   - Left strings: left = down, right = up
 *   - Right strings: left = up, right = down
 * - Per-voice volume mixing based on vertical pluck position
 * - Matching tension/spring sounds to BenderPlugin (no hum)
 * 
 * String 0 -> Voices 0,1 (LEFT - normal direction)
 * String 1 -> Voices 2,3 (LEFT - normal direction)
 * String 2 -> Voices 4,5 (RIGHT - inverted direction)
 * String 3 -> Voices 6,7 (RIGHT - inverted direction)
 *
 * Port Map:
 * 0..7: Voice Bend Outputs (CV)
 * 8..15: Voice Mix Outputs (CV)
 * 16: Audio Output (Effects mix)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class PerStringBenderPlugin(
    private val audioEngine: AudioEngine,
    private val resonatorPlugin: ResonatorPlugin,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.perstringbender",
        name = "Per-String Bender",
        author = "Balch"
    )

    override val ports: List<Port> = buildList {
        // Voice Bend Outputs (0-7)
        for (i in 0..7) {
            add(AudioPort(i, "bend_$i", "Bend Voice $i", false))
        }
        // Voice Mix Outputs (8-15)
        for (i in 0..7) {
            add(AudioPort(8 + i, "mix_$i", "Mix Voice $i", false))
        }
        // Audio Output (16)
        add(AudioPort(16, "audio_out", "Audio Output", false))
    }

    companion object {
        private const val NUM_STRINGS = 4
        private const val MAX_BEND_SEMITONES = 12.0 // 1 octave bend range per string
        private const val SPRING_DURATION_MS = 800
    }

    // Per-string bend state
    private data class StringBenderState(
        var bendAmount: Float = 0f,      // -1 to +1, after direction adjustment
        var rawDeflection: Float = 0f,   // Raw deflection for tracking
        var voiceMix: Float = 0.5f,      // 0=voice A only, 0.5=both, 1=voice B only
        var isActive: Boolean = false,   // Currently being touched
        var wasActive: Boolean = false,  // Was active in previous call (for envelope triggers)
        var triggeredVoice: Boolean = false, // Did we trigger the voice on pluck?
        var baseFrequency: Float = 440f  // Base frequency for this string
    )
    
    private val stringStates = Array(NUM_STRINGS) { StringBenderState() }

    // Per-voice pitch bend outputs (connect to voice frequency modulation)
    private val voiceBendOutputs = Array(8) { dspFactory.createPassThrough() }
    
    // Per-voice volume mix outputs (connect to voice VCA)
    private val voiceMixOutputs = Array(8) { dspFactory.createPassThrough() }
    
    // Per-string spring monitors for UI feedback
    private val stringBendMonitors = Array(NUM_STRINGS) { dspFactory.createPeakFollower() }
    
    // ═══════════════════════════════════════════════════════════
    // TENSION SOUND
    // ═══════════════════════════════════════════════════════════
    private val tensionOscillators = Array(NUM_STRINGS) { dspFactory.createSineOscillator() }
    private val tensionEnvelopes = Array(NUM_STRINGS) { dspFactory.createEnvelope() }
    private val tensionVcas = Array(NUM_STRINGS) { dspFactory.createMultiply() }
    private val tensionRamps = Array(NUM_STRINGS) { dspFactory.createLinearRamp() }  // Smooth level changes
    private val tensionGains = Array(NUM_STRINGS) { dspFactory.createMultiply() }
    
    // ═══════════════════════════════════════════════════════════
    // SPRING SOUND
    // ═══════════════════════════════════════════════════════════
    private val springOscillators = Array(NUM_STRINGS) { dspFactory.createSineOscillator() }
    private val springEnvelopes = Array(NUM_STRINGS) { dspFactory.createEnvelope() }
    private val springVcas = Array(NUM_STRINGS) { dspFactory.createMultiply() }
    private val springFreqBases = Array(NUM_STRINGS) { dspFactory.createMultiplyAdd() }
    
    private val wobbleLfos = Array(NUM_STRINGS) { dspFactory.createSineOscillator() }
    private val wobbleDepths = Array(NUM_STRINGS) { dspFactory.createMultiply() }
    private val wobbleMixers = Array(NUM_STRINGS) { dspFactory.createAdd() }
    private val springGains = Array(NUM_STRINGS) { dspFactory.createMultiply() }
    
    // ═══════════════════════════════════════════════════════════
    // PLUCK SOUND
    // ═══════════════════════════════════════════════════════════
    private val pluckOscillators = Array(NUM_STRINGS) { dspFactory.createSineOscillator() }
    private val pluckEnvelopes = Array(NUM_STRINGS) { dspFactory.createEnvelope() }
    private val pluckVcas = Array(NUM_STRINGS) { dspFactory.createMultiply() }
    private val pluckGains = Array(NUM_STRINGS) { dspFactory.createMultiply() }
    
    // ═══════════════════════════════════════════════════════════
    // SLIDE SOUND
    // ═══════════════════════════════════════════════════════════
    private val slideOscillators = Array(NUM_STRINGS) { dspFactory.createSquareOscillator() }
    private val slideLfos = Array(NUM_STRINGS) { dspFactory.createSineOscillator() }  // Rapid modulation
    private val slideFreqMixers = Array(NUM_STRINGS) { dspFactory.createAdd() }
    private val slideRamps = Array(NUM_STRINGS) { dspFactory.createLinearRamp() }
    private val slideGains = Array(NUM_STRINGS) { dspFactory.createMultiply() }
    
    // Additional mixers for all sound sources
    private val pluckMixerA = dspFactory.createAdd()
    private val pluckMixerB = dspFactory.createAdd()
    private val pluckMixerFinal = dspFactory.createAdd()
    
    private val slideMixerA = dspFactory.createAdd()
    private val slideMixerB = dspFactory.createAdd()
    private val slideMixerFinal = dspFactory.createAdd()
    
    // Audio mixers - combine all sources
    private val tensionMixerA = dspFactory.createAdd()
    private val tensionMixerB = dspFactory.createAdd()
    private val tensionMixerFinal = dspFactory.createAdd()
    
    private val springMixerA = dspFactory.createAdd()
    private val springMixerB = dspFactory.createAdd()
    private val springMixerFinal = dspFactory.createAdd()
    
    // Final mix stages
    private val effectsMixerA = dspFactory.createAdd()  // tension + spring
    private val effectsMixerB = dspFactory.createAdd()  // pluck + slide
    private val audioMixer = dspFactory.createAdd()     // all effects
    private val audioOutputProxy = dspFactory.createPassThrough()
    
    // State for gesture tracking
    private data class GestureState(
        var lastY: Float = 0.5f,
        var lastX: Float = 0f,
        var lastTime: Long = 0L,
        var slideActive: Boolean = false
    )
    private val gestureStates = Array(NUM_STRINGS) { GestureState() }
    
    // Reactive flow for UI spring positions
    private val _springPositionFlow = MutableStateFlow(FloatArray(NUM_STRINGS))
    val springPositionFlow: StateFlow<FloatArray> = _springPositionFlow.asStateFlow()

    override val audioUnits: List<AudioUnit> = 
        voiceBendOutputs.toList() +
        voiceMixOutputs.toList() +
        stringBendMonitors.toList() +
        tensionOscillators.toList() +
        tensionEnvelopes.toList() +
        tensionVcas.toList() +
        tensionRamps.toList() +
        tensionGains.toList() +
        springOscillators.toList() +
        springEnvelopes.toList() +
        springVcas.toList() +
        springFreqBases.toList() +
        wobbleLfos.toList() +
        wobbleDepths.toList() +
        wobbleMixers.toList() +
        springGains.toList() +
        pluckOscillators.toList() +
        pluckEnvelopes.toList() +
        pluckVcas.toList() +
        pluckGains.toList() +
        slideOscillators.toList() +
        slideLfos.toList() +
        slideFreqMixers.toList() +
        slideRamps.toList() +
        slideGains.toList() +
        listOf(
            tensionMixerA, tensionMixerB, tensionMixerFinal,
            springMixerA, springMixerB, springMixerFinal,
            pluckMixerA, pluckMixerB, pluckMixerFinal,
            slideMixerA, slideMixerB, slideMixerFinal,
            effectsMixerA, effectsMixerB,
            audioMixer, audioOutputProxy
        )

    override val inputs: Map<String, AudioInput> = emptyMap()

    override val outputs: Map<String, AudioOutput> = buildMap {
        for (i in 0 until 8) {
            put("voiceBend$i", voiceBendOutputs[i].output)
            put("voiceMix$i", voiceMixOutputs[i].output)
        }
        put("audioOutput", audioOutputProxy.output)
    }

    override fun initialize() {
        stringBendMonitors.forEach { it.setHalfLife(0.016) }
        
        // TENSION SOUND SETUP
        for (i in 0 until NUM_STRINGS) {
            tensionOscillators[i].frequency.set(300.0 + i * 20.0)
            tensionOscillators[i].amplitude.set(0.4)
            
            tensionEnvelopes[i].setAttack(0.1)
            tensionEnvelopes[i].setDecay(0.1)
            tensionEnvelopes[i].setSustain(0.6)
            tensionEnvelopes[i].setRelease(0.2)
            
            tensionOscillators[i].output.connect(tensionVcas[i].inputA)
            tensionEnvelopes[i].output.connect(tensionVcas[i].inputB)
            tensionVcas[i].output.connect(tensionGains[i].inputA)
            
            tensionRamps[i].time.set(0.02)
            tensionRamps[i].input.set(0.0)
            tensionRamps[i].output.connect(tensionGains[i].inputB)
        }
        
        // SPRING SOUND SETUP
        for (i in 0 until NUM_STRINGS) {
            springOscillators[i].amplitude.set(0.5)
            wobbleLfos[i].frequency.set(8.0)
            wobbleLfos[i].amplitude.set(1.0)
            wobbleLfos[i].output.connect(wobbleDepths[i].inputA)
            wobbleDepths[i].inputB.set(80.0)
            springFreqBases[i].inputB.set(-200.0)
            springFreqBases[i].inputC.set(500.0)
            springFreqBases[i].output.connect(wobbleMixers[i].inputA)
            wobbleDepths[i].output.connect(wobbleMixers[i].inputB)
            wobbleMixers[i].output.connect(springOscillators[i].frequency)
            springEnvelopes[i].setAttack(0.002)
            springEnvelopes[i].setDecay(0.5)
            springEnvelopes[i].setSustain(0.0)
            springEnvelopes[i].setRelease(0.3)
            springEnvelopes[i].output.connect(springVcas[i].inputB)
            springEnvelopes[i].output.connect(springFreqBases[i].inputA)
            springEnvelopes[i].output.connect(wobbleDepths[i].inputB)
            springOscillators[i].output.connect(springVcas[i].inputA)
            springVcas[i].output.connect(springGains[i].inputA)
            springGains[i].inputB.set(0.8)
        }
        
        // Mixers wiring
        tensionGains[0].output.connect(tensionMixerA.inputA)
        tensionGains[1].output.connect(tensionMixerA.inputB)
        tensionGains[2].output.connect(tensionMixerB.inputA)
        tensionGains[3].output.connect(tensionMixerB.inputB)
        tensionMixerA.output.connect(tensionMixerFinal.inputA)
        tensionMixerB.output.connect(tensionMixerFinal.inputB)
        
        springGains[0].output.connect(springMixerA.inputA)
        springGains[1].output.connect(springMixerA.inputB)
        springGains[2].output.connect(springMixerB.inputA)
        springGains[3].output.connect(springMixerB.inputB)
        springMixerA.output.connect(springMixerFinal.inputA)
        springMixerB.output.connect(springMixerFinal.inputB)
        
        // PLUCK SOUND SETUP
        for (i in 0 until NUM_STRINGS) {
            val baseFreq = 400.0 + i * 150.0
            pluckOscillators[i].frequency.set(baseFreq)
            pluckOscillators[i].amplitude.set(0.4)
            pluckEnvelopes[i].setAttack(0.001)
            pluckEnvelopes[i].setDecay(0.08)
            pluckEnvelopes[i].setSustain(0.0)
            pluckEnvelopes[i].setRelease(0.05)
            pluckOscillators[i].output.connect(pluckVcas[i].inputA)
            pluckEnvelopes[i].output.connect(pluckVcas[i].inputB)
            pluckVcas[i].output.connect(pluckGains[i].inputA)
            pluckGains[i].inputB.set(0.6)
        }
        
        // SLIDE SOUND SETUP
        for (i in 0 until NUM_STRINGS) {
            val baseFreq = 200.0 + i * 100.0
            slideOscillators[i].amplitude.set(0.15)
            slideLfos[i].frequency.set(40.0 + i * 10.0)
            slideLfos[i].amplitude.set(50.0)
            slideFreqMixers[i].inputA.set(baseFreq)
            slideLfos[i].output.connect(slideFreqMixers[i].inputB)
            slideFreqMixers[i].output.connect(slideOscillators[i].frequency)
            slideRamps[i].time.set(0.03)
            slideRamps[i].input.set(0.0)
            slideRamps[i].output.connect(slideGains[i].inputB)
            slideOscillators[i].output.connect(slideGains[i].inputA)
        }
        
        // Final mixer wiring
        pluckGains[0].output.connect(pluckMixerA.inputA)
        pluckGains[1].output.connect(pluckMixerA.inputB)
        pluckGains[2].output.connect(pluckMixerB.inputA)
        pluckGains[3].output.connect(pluckMixerB.inputB)
        pluckMixerA.output.connect(pluckMixerFinal.inputA)
        pluckMixerB.output.connect(pluckMixerFinal.inputB)
        
        slideGains[0].output.connect(slideMixerA.inputA)
        slideGains[1].output.connect(slideMixerA.inputB)
        slideGains[2].output.connect(slideMixerB.inputA)
        slideGains[3].output.connect(slideMixerB.inputB)
        slideMixerA.output.connect(slideMixerFinal.inputA)
        slideMixerB.output.connect(slideMixerFinal.inputB)
        
        tensionMixerFinal.output.connect(effectsMixerA.inputA)
        springMixerFinal.output.connect(effectsMixerA.inputB)
        pluckMixerFinal.output.connect(effectsMixerB.inputA)
        slideMixerFinal.output.connect(effectsMixerB.inputB)
        effectsMixerA.output.connect(audioMixer.inputA)
        effectsMixerB.output.connect(audioMixer.inputB)
        audioMixer.output.connect(audioOutputProxy.input)
        
        voiceMixOutputs.forEach { it.input.set(1.0) }
        voiceBendOutputs.forEach { it.input.set(0.0) }

        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    private fun applyDirectionForString(stringIndex: Int, rawDeflection: Float): Float {
        return if (stringIndex < 2) {
            rawDeflection
        } else {
            -rawDeflection
        }
    }

    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float, voiceIsPlaying: Boolean = true): Boolean {
        if (stringIndex !in 0 until NUM_STRINGS) return false
        
        val state = stringStates[stringIndex]
        
        // Store raw and apply direction
        state.rawDeflection = bendAmount.coerceIn(-1f, 1f)
        state.bendAmount = applyDirectionForString(stringIndex, state.rawDeflection)
        state.voiceMix = voiceMix.coerceIn(0f, 1f)
        state.isActive = true
        
        // Calculate pitch bend
        val normalizedBend = state.bendAmount
        val tensionCurve = normalizedBend.pow(3) 
        val semitones = tensionCurve * MAX_BEND_SEMITONES
        val frequencyMultiplier = 2.0.pow(semitones / 12.0) - 1.0
        
        // Apply to both voices in the duo
        val voiceA = stringIndex * 2
        val voiceB = stringIndex * 2 + 1
        
        voiceBendOutputs[voiceA].input.set(frequencyMultiplier)
        voiceBendOutputs[voiceB].input.set(frequencyMultiplier)
        
        // Calculate voice mix volumes
        val voiceAVolume = when {
            voiceMix <= 0.25f -> 1.0f
            voiceMix >= 0.75f -> 1.0f - (voiceMix - 0.75f) / 0.25f
            else -> 1.0f
        }
        val voiceBVolume = when {
            voiceMix <= 0.25f -> voiceMix / 0.25f
            voiceMix >= 0.75f -> 1.0f
            else -> 1.0f
        }
        
        voiceMixOutputs[voiceA].input.set(voiceAVolume.toDouble())
        voiceMixOutputs[voiceB].input.set(voiceBVolume.toDouble())
        
        // Effects control
        val tension = normalizedBend.absoluteValue
        val tensionFreq = 300.0 + (tension * 200.0) + stringIndex * 20.0
        tensionOscillators[stringIndex].frequency.set(tensionFreq)
        
        val tensionLevel = 0.0 // Muted by default logic
        tensionRamps[stringIndex].input.set(tensionLevel)
        
        // Automatic slide handling
        val gesture = gestureStates[stringIndex]
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val deltaY = (voiceMix - gesture.lastY).absoluteValue
        val deltaTime = (currentTime - gesture.lastTime).coerceAtLeast(1L)
        val slideVelocity = deltaY / deltaTime * 1000f
        
        val slideThreshold = 0.5f 
        val slideLevel = 0.0f // Muted by default logic
        
        val slideBaseFreq = 150.0 + (1.0 - voiceMix) * 400.0 + stringIndex * 50.0
        slideFreqMixers[stringIndex].inputA.set(slideBaseFreq)
        slideRamps[stringIndex].input.set(slideLevel.toDouble())
        
        gesture.lastY = voiceMix
        gesture.lastX = bendAmount
        gesture.lastTime = currentTime
        gesture.slideActive = slideLevel > 0.1f
        
        // Trigger envelope
        val isActive = tension > 0.05f
        if (isActive && !state.wasActive) {
            tensionEnvelopes[stringIndex].input.set(1.0)
            state.wasActive = true
            
            if (!voiceIsPlaying) {
                state.triggeredVoice = true
                return true
            }
        }
        
        stringBendMonitors[stringIndex].input.set(state.rawDeflection.toDouble())
        
        return false
    }

    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float): Boolean {
        // Pass false to indicate we want this interaction to be capable of triggering the voice
        return setStringBend(stringIndex, bendAmount, voiceMix, voiceIsPlaying = false)
    }

    fun releaseString(stringIndex: Int): Pair<Int, Boolean> {
        if (stringIndex !in 0 until NUM_STRINGS) return Pair(0, false)
        
        val state = stringStates[stringIndex]
        if (!state.isActive) return Pair(0, false)
        
        state.isActive = false
        val shouldReleaseVoice = state.triggeredVoice
        state.triggeredVoice = false
        
        val pullDistance = state.rawDeflection.absoluteValue
        val gesture = gestureStates[stringIndex]
        
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val deltaTime = (currentTime - gesture.lastTime).coerceAtLeast(1L)
        val releaseVelocity = pullDistance / deltaTime * 1000f
        
        // Reset outputs
        val voiceA = stringIndex * 2
        val voiceB = stringIndex * 2 + 1
        voiceBendOutputs[voiceA].input.set(0.0)
        voiceBendOutputs[voiceB].input.set(0.0)
        voiceMixOutputs[voiceA].input.set(1.0)
        voiceMixOutputs[voiceB].input.set(1.0)
        
        slideRamps[stringIndex].input.set(0.0)
        gesture.slideActive = false
        
        if (state.wasActive) {
            tensionEnvelopes[stringIndex].input.set(0.0)
            tensionRamps[stringIndex].input.set(0.0)
            state.wasActive = false
        }
        
        // Pluck logic
        val pluckThreshold = 1.5f
        if (releaseVelocity > pluckThreshold && pullDistance > 0.15f) {
            val velocityPitchMod = 1.0 + (releaseVelocity - pluckThreshold) * 0.1
            val slideBend = slideBarPosition
            val tensionCurve = slideBend * (1.0 + slideBend.absoluteValue * 0.5)
            val slideSemitones = tensionCurve * MAX_BEND_SEMITONES * 0.5
            val slideMultiplier = 2.0.pow(slideSemitones / 12.0)
            
            val strumFreq = state.baseFrequency * velocityPitchMod * slideMultiplier
            resonatorPlugin.strum(strumFreq.toFloat())
        }
        
        state.bendAmount = 0f
        state.rawDeflection = 0f
        state.voiceMix = 0.5f
        gesture.lastY = 0.5f
        gesture.lastX = 0f
        gesture.lastTime = currentTime
        
        val springDuration = (SPRING_DURATION_MS * pullDistance.coerceIn(0.3f, 1f)).toInt()
        return Pair(springDuration, shouldReleaseVoice)
    }

    // Accessors
    fun getSpringPosition(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringBendMonitors[stringIndex].getCurrent().toFloat().coerceIn(-1f, 1f)
    }

    fun isStringActive(stringIndex: Int): Boolean {
        if (stringIndex !in 0 until NUM_STRINGS) return false
        return stringStates[stringIndex].isActive
    }

    fun getStringBend(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringStates[stringIndex].bendAmount
    }

    fun getRawDeflection(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringStates[stringIndex].rawDeflection
    }
    
    fun setStringFrequency(stringIndex: Int, frequency: Double) {
        if (stringIndex !in 0 until NUM_STRINGS) return
        pluckOscillators[stringIndex].frequency.set(frequency)
        stringStates[stringIndex].baseFrequency = frequency.toFloat()
    }

    fun resetAll() {
        for (i in 0 until NUM_STRINGS) {
            val state = stringStates[i]
            state.isActive = false
            state.wasActive = false
            state.triggeredVoice = false
            state.bendAmount = 0f
            state.rawDeflection = 0f
            state.voiceMix = 0.5f
            
            val voiceA = i * 2
            val voiceB = i * 2 + 1
            voiceBendOutputs[voiceA].input.set(0.0)
            voiceBendOutputs[voiceB].input.set(0.0)
            voiceMixOutputs[voiceA].input.set(1.0)
            voiceMixOutputs[voiceB].input.set(1.0)
            
            tensionEnvelopes[i].input.set(0.0)
            tensionRamps[i].input.set(0.0)
            slideRamps[i].input.set(0.0)
            
            gestureStates[i].lastY = 0.5f
            gestureStates[i].lastX = 0f
            gestureStates[i].slideActive = false
        }
        
        slideBarPosition = 0f
        slideBarVibratoDepth = 0f
        slideBarWasActive = false
    }

    // SLIDE BAR
    private var slideBarPosition = 0f
    private var slideBarLastX = 0.5f
    private var slideBarLastTime = 0L
    private var slideBarVibratoDepth = 0f
    private var slideBarWasActive = false
    
    fun setSlideBar(yPosition: Float, xPosition: Float) {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val deltaTime = (currentTime - slideBarLastTime).coerceAtLeast(1L)
        
        val deltaX = (xPosition - slideBarLastX).absoluteValue
        val wiggleVelocity = deltaX / deltaTime * 1000f
        
        val vibratoThreshold = 0.8f
        slideBarVibratoDepth = ((wiggleVelocity - vibratoThreshold) / 3f).coerceIn(0f, 1f)
        
        slideBarLastX = xPosition
        slideBarLastTime = currentTime
        slideBarPosition = yPosition.coerceIn(0f, 1f)
        
        val slideBend = slideBarPosition
        val isActive = slideBend > 0.02f
        
        for (i in 0 until NUM_STRINGS) {
            val vibratoOscillation = if (slideBarVibratoDepth > 0.05f) {
                kotlin.math.sin(currentTime * 0.03) * slideBarVibratoDepth * 0.3
            } else {
                0.0
            }
            
            val totalBend = slideBend + vibratoOscillation.toFloat()
            val tensionCurve = totalBend * (1.0 + totalBend.absoluteValue * 0.5)
            val semitones = tensionCurve * MAX_BEND_SEMITONES * 0.5
            val frequencyMultiplier = 2.0.pow(semitones / 12.0) - 1.0
            
            val voiceA = i * 2
            val voiceB = i * 2 + 1
            
            voiceBendOutputs[voiceA].input.set(frequencyMultiplier)
            voiceBendOutputs[voiceB].input.set(frequencyMultiplier)
        }
        
        if (isActive) {
            val tensionFreq = 250.0 + (slideBend * 150.0)
            tensionOscillators[0].frequency.set(tensionFreq)
            
            val tensionLevel = slideBend * 0.015
            tensionRamps[0].input.set(tensionLevel)
            
            if (!slideBarWasActive) {
                tensionEnvelopes[0].input.set(1.0)
                slideBarWasActive = true
            }
        } else if (slideBarWasActive) {
            tensionEnvelopes[0].input.set(0.0)
            tensionRamps[0].input.set(0.0)
        }
    }
    
    fun releaseSlideBar() {
        val wasActive = slideBarWasActive
        slideBarPosition = 0f
        slideBarVibratoDepth = 0f
        slideBarWasActive = false
        
        if (wasActive) {
            tensionEnvelopes[0].input.set(0.0)
            tensionRamps[0].input.set(0.0)
            springEnvelopes[0].input.set(1.0)
            springEnvelopes[0].input.set(0.0)
        }
        
        for (i in 0 until NUM_STRINGS) {
            if (!stringStates[i].isActive) {
                val voiceA = i * 2
                val voiceB = i * 2 + 1
                voiceBendOutputs[voiceA].input.set(0.0)
                voiceBendOutputs[voiceB].input.set(0.0)
            }
        }
    }
    
    fun getSlideBarPosition(): Float = slideBarPosition
    fun getSlideBarVibratoDepth(): Float = slideBarVibratoDepth
}
