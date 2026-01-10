package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
 * - Matching tension/spring sounds to DspBenderPlugin (no hum)
 * 
 * String 0 -> Voices 0,1 (LEFT - normal direction)
 * String 1 -> Voices 2,3 (LEFT - normal direction)
 * String 2 -> Voices 4,5 (RIGHT - inverted direction)
 * String 3 -> Voices 6,7 (RIGHT - inverted direction)
 */
@OptIn(ExperimentalTime::class)
@Inject
@ContributesIntoSet(AppScope::class)
class DspPerStringBenderPlugin(
    private val audioEngine: AudioEngine,
    private val resonatorPlugin: DspResonatorPlugin
) : DspPlugin {

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
    private val voiceBendOutputs = Array(8) { audioEngine.createPassThrough() }
    
    // Per-voice volume mix outputs (connect to voice VCA)
    private val voiceMixOutputs = Array(8) { audioEngine.createPassThrough() }
    
    // Per-string spring monitors for UI feedback
    private val stringBendMonitors = Array(NUM_STRINGS) { audioEngine.createPeakFollower() }
    
    // ═══════════════════════════════════════════════════════════
    // TENSION SOUND - Very subtle, matching DspBenderPlugin
    // ═══════════════════════════════════════════════════════════
    private val tensionOscillators = Array(NUM_STRINGS) { audioEngine.createSineOscillator() }
    private val tensionEnvelopes = Array(NUM_STRINGS) { audioEngine.createEnvelope() }
    private val tensionVcas = Array(NUM_STRINGS) { audioEngine.createMultiply() }
    private val tensionRamps = Array(NUM_STRINGS) { audioEngine.createLinearRamp() }  // Smooth level changes
    private val tensionGains = Array(NUM_STRINGS) { audioEngine.createMultiply() }
    
    // ═══════════════════════════════════════════════════════════
    // SPRING SOUND - Matching DspBenderPlugin's wobble effect
    // ═══════════════════════════════════════════════════════════
    private val springOscillators = Array(NUM_STRINGS) { audioEngine.createSineOscillator() }
    private val springEnvelopes = Array(NUM_STRINGS) { audioEngine.createEnvelope() }
    private val springVcas = Array(NUM_STRINGS) { audioEngine.createMultiply() }
    private val springFreqBases = Array(NUM_STRINGS) { audioEngine.createMultiplyAdd() }
    
    private val wobbleLfos = Array(NUM_STRINGS) { audioEngine.createSineOscillator() }
    private val wobbleDepths = Array(NUM_STRINGS) { audioEngine.createMultiply() }
    private val wobbleMixers = Array(NUM_STRINGS) { audioEngine.createAdd() }
    private val springGains = Array(NUM_STRINGS) { audioEngine.createMultiply() }
    
    // ═══════════════════════════════════════════════════════════
    // PLUCK SOUND - Percussive ping on quick release
    // Pitch based on string index and release velocity
    // ═══════════════════════════════════════════════════════════
    private val pluckOscillators = Array(NUM_STRINGS) { audioEngine.createSineOscillator() }
    private val pluckEnvelopes = Array(NUM_STRINGS) { audioEngine.createEnvelope() }
    private val pluckVcas = Array(NUM_STRINGS) { audioEngine.createMultiply() }
    private val pluckGains = Array(NUM_STRINGS) { audioEngine.createMultiply() }
    
    // ═══════════════════════════════════════════════════════════
    // SLIDE SOUND - Textured sound when sliding vertically
    // Fast-modulated oscillator creates "scratchy" texture
    // ═══════════════════════════════════════════════════════════
    private val slideOscillators = Array(NUM_STRINGS) { audioEngine.createSquareOscillator() }
    private val slideLfos = Array(NUM_STRINGS) { audioEngine.createSineOscillator() }  // Rapid modulation
    private val slideFreqMixers = Array(NUM_STRINGS) { audioEngine.createAdd() }
    private val slideRamps = Array(NUM_STRINGS) { audioEngine.createLinearRamp() }
    private val slideGains = Array(NUM_STRINGS) { audioEngine.createMultiply() }
    
    // Additional mixers for all sound sources
    private val pluckMixerA = audioEngine.createAdd()
    private val pluckMixerB = audioEngine.createAdd()
    private val pluckMixerFinal = audioEngine.createAdd()
    
    private val slideMixerA = audioEngine.createAdd()
    private val slideMixerB = audioEngine.createAdd()
    private val slideMixerFinal = audioEngine.createAdd()
    
    // Audio mixers - combine all sources
    private val tensionMixerA = audioEngine.createAdd()
    private val tensionMixerB = audioEngine.createAdd()
    private val tensionMixerFinal = audioEngine.createAdd()
    
    private val springMixerA = audioEngine.createAdd()
    private val springMixerB = audioEngine.createAdd()
    private val springMixerFinal = audioEngine.createAdd()
    
    // Final mix stages
    private val effectsMixerA = audioEngine.createAdd()  // tension + spring
    private val effectsMixerB = audioEngine.createAdd()  // pluck + slide
    private val audioMixer = audioEngine.createAdd()     // all effects
    private val audioOutputProxy = audioEngine.createPassThrough()
    
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

    override val audioUnits: List<AudioUnit>
        get() = voiceBendOutputs.toList() +
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
                listOf(tensionMixerA, tensionMixerB, tensionMixerFinal,
                       springMixerA, springMixerB, springMixerFinal,
                       pluckMixerA, pluckMixerB, pluckMixerFinal,
                       slideMixerA, slideMixerB, slideMixerFinal,
                       effectsMixerA, effectsMixerB,
                       audioMixer, audioOutputProxy)

    override val inputs: Map<String, AudioInput>
        get() = emptyMap()

    override val outputs: Map<String, AudioOutput>
        get() = buildMap {
            for (i in 0 until 8) {
                put("voiceBend$i", voiceBendOutputs[i].output)
                put("voiceMix$i", voiceMixOutputs[i].output)
            }
            put("audioOutput", audioOutputProxy.output)
        }

    override fun initialize() {
        stringBendMonitors.forEach { it.setHalfLife(0.016) }
        
        // ═══════════════════════════════════════════════════════════
        // TENSION SOUND SETUP - Very subtle, matching DspBenderPlugin
        // ═══════════════════════════════════════════════════════════
        for (i in 0 until NUM_STRINGS) {
            tensionOscillators[i].frequency.set(300.0 + i * 20.0)
            tensionOscillators[i].amplitude.set(0.4) // Louder tension sound
            
            tensionEnvelopes[i].setAttack(0.1)  // Slow attack to fade in
            tensionEnvelopes[i].setDecay(0.1)
            tensionEnvelopes[i].setSustain(0.6)
            tensionEnvelopes[i].setRelease(0.2)
            
            tensionOscillators[i].output.connect(tensionVcas[i].inputA)
            tensionEnvelopes[i].output.connect(tensionVcas[i].inputB)
            tensionVcas[i].output.connect(tensionGains[i].inputA)
            
            // Tension level ramp for click-free transitions
            tensionRamps[i].time.set(0.02) // 20ms ramp time
            tensionRamps[i].input.set(0.0)
            tensionRamps[i].output.connect(tensionGains[i].inputB)
        }
        
        // ═══════════════════════════════════════════════════════════
        // SPRING SOUND SETUP - Matching DspBenderPlugin's wobble
        // ═══════════════════════════════════════════════════════════
        for (i in 0 until NUM_STRINGS) {
            springOscillators[i].amplitude.set(0.5) // Louder spring sound
            
            // Wobble LFO - 8Hz to match DspBenderPlugin
            wobbleLfos[i].frequency.set(8.0)
            wobbleLfos[i].amplitude.set(1.0)
            
            // Wobble depth modulated by envelope
            wobbleLfos[i].output.connect(wobbleDepths[i].inputA)
            wobbleDepths[i].inputB.set(80.0) // ±80Hz wobble depth
            
            // Spring frequency base: envelope controls sweep
            springFreqBases[i].inputB.set(-200.0) // Sweep down 200Hz
            springFreqBases[i].inputC.set(500.0)  // Base 500Hz
            
            // Mix base freq + wobble -> spring oscillator frequency
            springFreqBases[i].output.connect(wobbleMixers[i].inputA)
            wobbleDepths[i].output.connect(wobbleMixers[i].inputB)
            wobbleMixers[i].output.connect(springOscillators[i].frequency)
            
            // Spring envelope
            springEnvelopes[i].setAttack(0.002)
            springEnvelopes[i].setDecay(0.5)
            springEnvelopes[i].setSustain(0.0)
            springEnvelopes[i].setRelease(0.3)
            
            // Envelope controls VCA, freq sweep, and wobble depth
            springEnvelopes[i].output.connect(springVcas[i].inputB)
            springEnvelopes[i].output.connect(springFreqBases[i].inputA)
            springEnvelopes[i].output.connect(wobbleDepths[i].inputB)
            
            // Wire oscillator through VCA and gain
            springOscillators[i].output.connect(springVcas[i].inputA)
            springVcas[i].output.connect(springGains[i].inputA)
            springGains[i].inputB.set(0.8) // 80% spring volume - louder!
        }
        
        // Mix tension sounds: 0+1 -> A, 2+3 -> B, A+B -> Final
        tensionGains[0].output.connect(tensionMixerA.inputA)
        tensionGains[1].output.connect(tensionMixerA.inputB)
        tensionGains[2].output.connect(tensionMixerB.inputA)
        tensionGains[3].output.connect(tensionMixerB.inputB)
        tensionMixerA.output.connect(tensionMixerFinal.inputA)
        tensionMixerB.output.connect(tensionMixerFinal.inputB)
        
        // Mix spring sounds: 0+1 -> A, 2+3 -> B, A+B -> Final
        springGains[0].output.connect(springMixerA.inputA)
        springGains[1].output.connect(springMixerA.inputB)
        springGains[2].output.connect(springMixerB.inputA)
        springGains[3].output.connect(springMixerB.inputB)
        springMixerA.output.connect(springMixerFinal.inputA)
        springMixerB.output.connect(springMixerFinal.inputB)
        
        // ═══════════════════════════════════════════════════════════
        // PLUCK SOUND SETUP - Percussive ping on release
        // ═══════════════════════════════════════════════════════════
        for (i in 0 until NUM_STRINGS) {
            // Base frequency depends on string (higher strings = higher pitch)
            val baseFreq = 400.0 + i * 150.0  // 400Hz, 550Hz, 700Hz, 850Hz
            pluckOscillators[i].frequency.set(baseFreq)
            pluckOscillators[i].amplitude.set(0.4)
            
            // Very fast envelope for percussive attack
            pluckEnvelopes[i].setAttack(0.001)   // 1ms attack
            pluckEnvelopes[i].setDecay(0.08)     // 80ms decay
            pluckEnvelopes[i].setSustain(0.0)    // No sustain
            pluckEnvelopes[i].setRelease(0.05)   // 50ms release
            
            // Wire: osc -> VCA -> gain
            pluckOscillators[i].output.connect(pluckVcas[i].inputA)
            pluckEnvelopes[i].output.connect(pluckVcas[i].inputB)
            pluckVcas[i].output.connect(pluckGains[i].inputA)
            pluckGains[i].inputB.set(0.6)  // 60% volume
        }
        
        // ═══════════════════════════════════════════════════════════
        // SLIDE SOUND SETUP - Scratchy texture during vertical slide
        // ═══════════════════════════════════════════════════════════
        for (i in 0 until NUM_STRINGS) {
            // Base frequency depends on string
            val baseFreq = 200.0 + i * 100.0
            slideOscillators[i].amplitude.set(0.15)  // Subtle
            
            // Rapid LFO modulates frequency for scratchy texture
            slideLfos[i].frequency.set(40.0 + i * 10.0)  // 40-70Hz modulation
            slideLfos[i].amplitude.set(50.0)  // ±50Hz variation
            
            // Mix base freq + LFO wobble
            slideFreqMixers[i].inputA.set(baseFreq)
            slideLfos[i].output.connect(slideFreqMixers[i].inputB)
            slideFreqMixers[i].output.connect(slideOscillators[i].frequency)
            
            // Volume ramp for smooth transitions
            slideRamps[i].time.set(0.03)  // 30ms ramp
            slideRamps[i].input.set(0.0)  // Start silent
            slideRamps[i].output.connect(slideGains[i].inputB)
            
            // Wire oscillator through gain
            slideOscillators[i].output.connect(slideGains[i].inputA)
        }
        
        // Mix pluck sounds: 0+1 -> A, 2+3 -> B, A+B -> Final
        pluckGains[0].output.connect(pluckMixerA.inputA)
        pluckGains[1].output.connect(pluckMixerA.inputB)
        pluckGains[2].output.connect(pluckMixerB.inputA)
        pluckGains[3].output.connect(pluckMixerB.inputB)
        pluckMixerA.output.connect(pluckMixerFinal.inputA)
        pluckMixerB.output.connect(pluckMixerFinal.inputB)
        
        // Mix slide sounds: 0+1 -> A, 2+3 -> B, A+B -> Final
        slideGains[0].output.connect(slideMixerA.inputA)
        slideGains[1].output.connect(slideMixerA.inputB)
        slideGains[2].output.connect(slideMixerB.inputA)
        slideGains[3].output.connect(slideMixerB.inputB)
        slideMixerA.output.connect(slideMixerFinal.inputA)
        slideMixerB.output.connect(slideMixerFinal.inputB)
        
        // Final mix: (tension + spring) + (pluck + slide) -> output
        tensionMixerFinal.output.connect(effectsMixerA.inputA)
        springMixerFinal.output.connect(effectsMixerA.inputB)
        pluckMixerFinal.output.connect(effectsMixerB.inputA)
        slideMixerFinal.output.connect(effectsMixerB.inputB)
        effectsMixerA.output.connect(audioMixer.inputA)
        effectsMixerB.output.connect(audioMixer.inputB)
        audioMixer.output.connect(audioOutputProxy.input)
        
        // Initialize all outputs
        voiceMixOutputs.forEach { it.input.set(1.0) }
        voiceBendOutputs.forEach { it.input.set(0.0) }
    }

    /**
     * Apply direction inversion based on string position.
     * REVERSED: Left strings (0,1) are normal so pulling outward (left/-) = pitch down
     * Right strings (2,3): inverted so pulling outward (right/+) = pitch down
     * Result: both sides pulling outward = pitch DOWN together
     */
    private fun applyDirectionForString(stringIndex: Int, rawDeflection: Float): Float {
        return if (stringIndex < 2) {
            rawDeflection // Normal for LEFT strings (pulling left/outward = negative/pitch down)
        } else {
            -rawDeflection  // Invert for RIGHT strings (pulling right/outward = negative/pitch down)
        }
    }

    /**
     * Called when a string starts being plucked or is being bent.
     * 
     * @param stringIndex 0-3
     * @param bendAmount -1 to +1 (horizontal deflection - will be direction-adjusted internally)
     * @param voiceMix 0 to 1 (0=top/voice A, 0.5=center/both, 1=bottom/voice B)
     * @param voiceIsPlaying Whether the associated voices are currently playing
     * @return true if we triggered the voice (caller should track for release)
     */
    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float, voiceIsPlaying: Boolean = true): Boolean {
        if (stringIndex !in 0 until NUM_STRINGS) return false
        
        val state = stringStates[stringIndex]
        val wasActive = state.isActive
        
        // Store raw and apply direction
        state.rawDeflection = bendAmount.coerceIn(-1f, 1f)
        state.bendAmount = applyDirectionForString(stringIndex, state.rawDeflection)
        state.voiceMix = voiceMix.coerceIn(0f, 1f)
        state.isActive = true
        
        // Calculate pitch bend with AGGRESSIVE EASE-IN
        // Flatten the center (stable pitch) and ramp up at edges
        // Cubic curve: x^3
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
        
        // ═══════════════════════════════════════════════════════════
        // TENSION SOUND CONTROL - Very subtle, matching DspBenderPlugin
        // ═══════════════════════════════════════════════════════════
        val tension = normalizedBend.absoluteValue
        
        // Tension frequency rises with bend (300-500Hz range)
        val tensionFreq = 300.0 + (tension * 200.0) + stringIndex * 20.0
        tensionOscillators[stringIndex].frequency.set(tensionFreq)
        
        // Tension level - SCALED DOWN TO 0 to rely purely on synth voices
        // val tensionLevel = tension * 0.10
        val tensionLevel = 0.0
        tensionRamps[stringIndex].input.set(tensionLevel.toDouble())  // Ramp for click-free
        
        // ═══════════════════════════════════════════════════════════
        // SLIDE DETECTION - Vertical movement creates scratchy sound
        // ═══════════════════════════════════════════════════════════
        val gesture = gestureStates[stringIndex]
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val deltaY = (voiceMix - gesture.lastY).absoluteValue
        val deltaTime = (currentTime - gesture.lastTime).coerceAtLeast(1L)
        val slideVelocity = deltaY / deltaTime * 1000f  // velocity per second
        
        // Activate slide if moving fast enough vertically
        val slideThreshold = 0.5f  // Velocity threshold
        // val slideLevel = ((slideVelocity - slideThreshold) / 2f).coerceIn(0f, 1f)
        val slideLevel = 0.0f // SILENCED
        
        // Slide frequency follows Y position (high at top, low at bottom)
        val slideBaseFreq = 150.0 + (1.0 - voiceMix) * 400.0 + stringIndex * 50.0
        slideFreqMixers[stringIndex].inputA.set(slideBaseFreq)
        slideRamps[stringIndex].input.set(slideLevel.toDouble())
        
        gesture.lastY = voiceMix
        gesture.lastX = bendAmount
        gesture.lastTime = currentTime
        gesture.slideActive = slideLevel > 0.1f
        
        // Trigger tension envelope when bend starts
        val isActive = tension > 0.05f
        if (isActive && !state.wasActive) {
            tensionEnvelopes[stringIndex].input.set(1.0)
            state.wasActive = true
            
            // If voice wasn't playing, we triggered it
            if (!voiceIsPlaying) {
                state.triggeredVoice = true
                return true
            }
        }
        
        // Update monitor for UI
        stringBendMonitors[stringIndex].input.set(state.rawDeflection.toDouble())
        
        return false
    }

    /**
     * Simplified version for when we don't need to track voice triggering.
     */
    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float): Boolean {
        // Pass false to indicate we want this interaction to be capable of triggering the voice
        return setStringBend(stringIndex, bendAmount, voiceMix, voiceIsPlaying = false)
    }

    /**
     * Called when a string is released.
     * @return Spring duration in ms for UI animation, and whether to release the voice
     */
    fun releaseString(stringIndex: Int): Pair<Int, Boolean> {
        if (stringIndex !in 0 until NUM_STRINGS) return Pair(0, false)
        
        val state = stringStates[stringIndex]
        if (!state.isActive) return Pair(0, false)
        
        state.isActive = false
        val shouldReleaseVoice = state.triggeredVoice
        state.triggeredVoice = false
        
        val pullDistance = state.rawDeflection.absoluteValue
        val gesture = gestureStates[stringIndex]
        
        // Calculate release velocity for pluck detection
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val deltaTime = (currentTime - gesture.lastTime).coerceAtLeast(1L)
        val releaseVelocity = pullDistance / deltaTime * 1000f  // per second
        
        // Reset outputs
        val voiceA = stringIndex * 2
        val voiceB = stringIndex * 2 + 1
        voiceBendOutputs[voiceA].input.set(0.0)
        voiceBendOutputs[voiceB].input.set(0.0)
        voiceMixOutputs[voiceA].input.set(1.0)
        voiceMixOutputs[voiceB].input.set(1.0)
        
        // Stop slide sound
        slideRamps[stringIndex].input.set(0.0)
        gesture.slideActive = false
        
        // Release tension and trigger spring (matching DspBenderPlugin logic)
        if (state.wasActive) {
            tensionEnvelopes[stringIndex].input.set(0.0)
            tensionRamps[stringIndex].input.set(0.0)  // Ramp down for click-free
            
            // Trigger spring sound
            /* SILENCED INTERNAL SPRING
            if (pullDistance > 0.05f) {
                springEnvelopes[stringIndex].input.set(1.0)
                springEnvelopes[stringIndex].input.set(0.0)
            }
            */
            state.wasActive = false
        }
        
        // ═══════════════════════════════════════════════════════════
        // PLUCK SOUND - Trigger on quick release with velocity
        // ═══════════════════════════════════════════════════════════
        val pluckThreshold = 1.5f  // Velocity threshold for pluck
        if (releaseVelocity > pluckThreshold && pullDistance > 0.15f) {
            // Pitch up for fast release, down for slow
            val velocityPitchMod = 1.0 + (releaseVelocity - pluckThreshold) * 0.1
            val basePitch = 400.0 + stringIndex * 150.0
            
            // Direction affects pitch: left release = lower, right release = higher
            val directionMod = if (state.bendAmount > 0) 1.2 else 0.8
            
            // Slide Bar affects pitch: Higher slider (lower Y) = higher pitch? 
            // Or just follow the global pitch bend?
            // Let's use the slideBar pitch multiplier we calculated in setSlideBar
            val slideBend = slideBarPosition // 0 to 1
            val tensionCurve = slideBend * (1.0 + slideBend.absoluteValue * 0.5)
            val slideSemitones = tensionCurve * MAX_BEND_SEMITONES * 0.5
            val slideMultiplier = 2.0.pow(slideSemitones / 12.0)
            
            // Trigger Rings Resonator Strum
            val strumFreq = state.baseFrequency * velocityPitchMod * slideMultiplier
            resonatorPlugin.strum(strumFreq.toFloat())
            
            // Legacy internal pluck sound (disabled)
            /*
            pluckOscillators[stringIndex].frequency.set(basePitch * velocityPitchMod * directionMod * slideMultiplier)
            
            // Trigger pluck envelope
            pluckEnvelopes[stringIndex].input.set(1.0)
            pluckEnvelopes[stringIndex].input.set(0.0)
            */
        }
        
        // Reset state
        state.bendAmount = 0f
        state.rawDeflection = 0f
        state.voiceMix = 0.5f
        gesture.lastY = 0.5f
        gesture.lastX = 0f
        gesture.lastTime = currentTime
        
        // Return spring duration proportional to bend distance
        val springDuration = (SPRING_DURATION_MS * pullDistance.coerceIn(0.3f, 1f)).toInt()
        return Pair(springDuration, shouldReleaseVoice)
    }

    /**
     * Get the current spring position for a string (for UI visualization).
     */
    fun getSpringPosition(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringBendMonitors[stringIndex].getCurrent().toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Check if a string is currently active (being touched).
     */
    fun isStringActive(stringIndex: Int): Boolean {
        if (stringIndex !in 0 until NUM_STRINGS) return false
        return stringStates[stringIndex].isActive
    }

    /**
     * Get the current bend amount for a string (direction-adjusted).
     */
    fun getStringBend(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringStates[stringIndex].bendAmount
    }

    /**
     * Get the raw deflection for a string (not direction-adjusted, for UI).
     */
    fun getRawDeflection(stringIndex: Int): Float {
        if (stringIndex !in 0 until NUM_STRINGS) return 0f
        return stringStates[stringIndex].rawDeflection
    }
    
    
    /**
     * Set the base frequency for a string's pluck sound.
     * Should match the current tuning of the associated voice.
     */
    fun setStringFrequency(stringIndex: Int, frequency: Double) {
        if (stringIndex !in 0 until NUM_STRINGS) return
        pluckOscillators[stringIndex].frequency.set(frequency)
        stringStates[stringIndex].baseFrequency = frequency.toFloat()
    }

    /**
     * Reset all string bender state to neutral.
     * Call this when switching away from the strings panel or when using voice pads' global bender.
     * Ensures clean state transition between different interaction modes.
     */
    fun resetAll() {
        // Release all active strings
        for (i in 0 until NUM_STRINGS) {
            val state = stringStates[i]
            state.isActive = false
            state.wasActive = false
            state.triggeredVoice = false
            state.bendAmount = 0f
            state.rawDeflection = 0f
            state.voiceMix = 0.5f
            
            // Reset voice outputs to neutral (0 = no bend, 1 = full volume)
            val voiceA = i * 2
            val voiceB = i * 2 + 1
            voiceBendOutputs[voiceA].input.set(0.0)
            voiceBendOutputs[voiceB].input.set(0.0)
            voiceMixOutputs[voiceA].input.set(1.0)
            voiceMixOutputs[voiceB].input.set(1.0)
            
            // Reset tension sounds
            tensionEnvelopes[i].input.set(0.0)
            tensionRamps[i].input.set(0.0)
            slideRamps[i].input.set(0.0)
            
            // Reset gesture state
            gestureStates[i].lastY = 0.5f
            gestureStates[i].lastX = 0f
            gestureStates[i].slideActive = false
        }
        
        // Reset slide bar state
        slideBarPosition = 0f
        slideBarVibratoDepth = 0f
        slideBarWasActive = false
    }

    // ═══════════════════════════════════════════════════════════
    // SLIDE BAR - Global pitch control across all strings
    // ═══════════════════════════════════════════════════════════
    
    private var slideBarPosition = 0f  // 0=top (no bend), 1=bottom (max bend)
    private var slideBarLastX = 0.5f
    private var slideBarLastTime = 0L
    private var slideBarVibratoDepth = 0f
    private var slideBarWasActive = false
    
    /**
     * Set the slide bar position and horizontal wiggle for vibrato.
     * 
     * @param yPosition 0 to 1 (0=top, 1=bottom) - controls pitch bend (down = higher pitch)
     * @param xPosition 0 to 1 (horizontal position) - used for vibrato detection
     */
    fun setSlideBar(yPosition: Float, xPosition: Float) {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val deltaTime = (currentTime - slideBarLastTime).coerceAtLeast(1L)
        
        // Calculate wiggle velocity for vibrato
        val deltaX = (xPosition - slideBarLastX).absoluteValue
        val wiggleVelocity = deltaX / deltaTime * 1000f
        
        // Vibrato depth based on wiggle speed (above threshold)
        val vibratoThreshold = 0.8f
        slideBarVibratoDepth = ((wiggleVelocity - vibratoThreshold) / 3f).coerceIn(0f, 1f)
        
        // Store for next frame
        slideBarLastX = xPosition
        slideBarLastTime = currentTime
        slideBarPosition = yPosition.coerceIn(0f, 1f)
        
        // Calculate pitch bend: 0.0=top (no bend), 1.0=bottom (max bend up)
        // User requested sliding down raises pitch
        val slideBend = slideBarPosition // 0 to 1
        
        // Determine if slide bar is actively bending
        val isActive = slideBend > 0.02f
        
        // Apply slide bend to ALL voices (first 8 = strings)
        for (i in 0 until NUM_STRINGS) {
            // Add vibrato as rapid oscillation
            val vibratoOscillation = if (slideBarVibratoDepth > 0.05f) {
                kotlin.math.sin(currentTime * 0.03) * slideBarVibratoDepth * 0.3
            } else {
                0.0
            }
            
            val totalBend = slideBend + vibratoOscillation.toFloat()
            
            // Calculate pitch modulation
            val tensionCurve = totalBend * (1.0 + totalBend.absoluteValue * 0.5)
            val semitones = tensionCurve * MAX_BEND_SEMITONES * 0.5  // Half range for slide bar
            val frequencyMultiplier = 2.0.pow(semitones / 12.0) - 1.0
            
            val voiceA = i * 2
            val voiceB = i * 2 + 1
            
            // ALWAYS apply slide bar pitch bend to voices
            // This is additive with any string bend the voice might have
            // The voice's benderInput receives both global bender AND per-string bender signals
            // So we apply the slide bar bend unconditionally
            voiceBendOutputs[voiceA].input.set(frequencyMultiplier)
            voiceBendOutputs[voiceB].input.set(frequencyMultiplier)
        }
        
        // ═══════════════════════════════════════════════════════════
        // TENSION SOUND CONTROL - Like DspBenderPlugin.setBend
        // ═══════════════════════════════════════════════════════════
        if (isActive) {
            // Tension frequency rises with slide amount (250Hz to 400Hz range)
            val tensionFreq = 250.0 + (slideBend * 150.0)
            tensionOscillators[0].frequency.set(tensionFreq)
            
            // Tension gain scales with slide intensity (subtle like global bender)
            val tensionLevel = slideBend * 0.015
            tensionRamps[0].input.set(tensionLevel)
            
            // Trigger tension envelope when slide bar first moves from rest
            if (!slideBarWasActive) {
                tensionEnvelopes[0].input.set(1.0)
                slideBarWasActive = true
            }
        } else if (slideBarWasActive) {
            // Release tension envelope when returning to rest
            tensionEnvelopes[0].input.set(0.0)
            tensionRamps[0].input.set(0.0)
        }
    }
    
    /**
     * Release the slide bar (spring back to center).
     */
    fun releaseSlideBar() {
        val wasActive = slideBarWasActive
        slideBarPosition = 0f // Reset to top
        slideBarVibratoDepth = 0f
        slideBarWasActive = false
        
        // Release tension envelope
        if (wasActive) {
            tensionEnvelopes[0].input.set(0.0)
            tensionRamps[0].input.set(0.0)
            
            // Trigger spring sound for slide bar (wobbling boing effect)
            springEnvelopes[0].input.set(1.0)
            springEnvelopes[0].input.set(0.0)
        }
        
        // Reset all voice bends controlled by slide bar
        // Note: only reset if string is not actively being bent
        for (i in 0 until NUM_STRINGS) {
            if (!stringStates[i].isActive) {
                val voiceA = i * 2
                val voiceB = i * 2 + 1
                voiceBendOutputs[voiceA].input.set(0.0)
                voiceBendOutputs[voiceB].input.set(0.0)
            }
        }
    }
    
    /**
     * Get current slide bar position for UI.
     */
    fun getSlideBarPosition(): Float = slideBarPosition
    
    /**
     * Get current vibrato depth for UI feedback.
     */
    fun getSlideBarVibratoDepth(): Float = slideBarVibratoDepth
}
