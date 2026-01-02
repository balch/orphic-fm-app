package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.random.Random

/**
 * DSP Plugin for bending the entire spectrum's pitch and timbre.
 * 
 * Creates a non-linear, semi-random pitch bend effect that:
 * - Bends all voices' pitch based on the input bend amount
 * - Adds subtle randomized micro-detuning for organic movement
 * - Modulates timbre (sharpness) slightly based on bend intensity
 * - Plays subtle tension sound while bending
 * - Plays a wobbling spring "boing" with multiple bounces on release
 * 
 * The effect responds to a "spring slider" control where:
 * - Center (0f) = normal pitch
 * - Pull up (+1f) = bend up with tension
 * - Pull down (-1f) = bend down with tension
 * - Release = spring back to center with wobbling acoustic effect
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspBenderPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    // Control signal path: bendInput -> nonlinearShaper -> modDepth -> output
    private val bendInputProxy = audioEngine.createPassThrough()
    private val bendDepthGain = audioEngine.createMultiply()
    
    // Non-linear shaping: creates exponential response curve for more natural feel
    private val nonlinearMixer = audioEngine.createMultiplyAdd()
    
    // Randomized micro-detuning LFO for organic movement
    private val randomLfo = audioEngine.createSineOscillator()
    private val randomDepthGain = audioEngine.createMultiply()
    private val randomMixer = audioEngine.createAdd()
    
    // Timbre modulation: bend also affects sharpness
    private val timbreModGain = audioEngine.createMultiply()
    
    // Output proxies
    private val pitchOutputProxy = audioEngine.createPassThrough()
    private val timbreOutputProxy = audioEngine.createPassThrough()
    
    // Peak follower for visual feedback
    private val bendMonitor = audioEngine.createPeakFollower()
    
    // ═══════════════════════════════════════════════════════════
    // TENSION SOUND - very subtle, low-frequency hum while bending
    // ═══════════════════════════════════════════════════════════
    private val tensionOsc = audioEngine.createSineOscillator()
    private val tensionEnvelope = audioEngine.createEnvelope()
    private val tensionVca = audioEngine.createMultiply()
    private val tensionGain = audioEngine.createMultiply()
    
    // ═══════════════════════════════════════════════════════════
    // SPRING SOUND - wobbling "boing" with multiple bounces
    // Uses an LFO to modulate the spring frequency for wobble effect
    // ═══════════════════════════════════════════════════════════
    private val springOsc = audioEngine.createSineOscillator()
    private val springEnvelope = audioEngine.createEnvelope()
    private val springVca = audioEngine.createMultiply()
    private val springFreqBase = audioEngine.createMultiplyAdd() // Base freq sweep
    
    // Wobble LFO - modulates spring frequency for bounce effect
    private val wobbleLfo = audioEngine.createSineOscillator()
    private val wobbleDepth = audioEngine.createMultiply()
    private val wobbleMixer = audioEngine.createAdd() // Combine base freq + wobble
    
    private val springGain = audioEngine.createMultiply() // Final volume control
    
    // Audio output mixer
    private val audioMixer = audioEngine.createAdd()
    private val audioOutputProxy = audioEngine.createPassThrough()

    // State
    private var _bendAmount = 0.0f
    private var _maxBendSemitones = 24.0f // Maximum bend range in semitones
    private var _randomDepth = 0.1f // How much random detuning (0-1)
    private var _timbreModulation = 0.3f // How much bend affects timbre
    private var _wasActive = false // Track if bend was active (for spring detection)
    
    override val audioUnits: List<AudioUnit> = listOf(
        bendInputProxy, bendDepthGain, nonlinearMixer,
        randomLfo, randomDepthGain, randomMixer,
        timbreModGain, pitchOutputProxy, timbreOutputProxy,
        bendMonitor,
        // Tension sound units
        tensionOsc, tensionEnvelope, tensionVca, tensionGain,
        // Spring sound units with wobble
        springOsc, springEnvelope, springVca, springFreqBase,
        wobbleLfo, wobbleDepth, wobbleMixer, springGain,
        audioMixer, audioOutputProxy
    )

    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            "bend" to bendInputProxy.input
        )

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "pitchOutput" to pitchOutputProxy.output,
            "timbreOutput" to timbreOutputProxy.output,
            "audioOutput" to audioOutputProxy.output // Sound effects output
        )

    override fun initialize() {
        // Set up random micro-detuning LFO
        randomLfo.frequency.set(1.5 + Random.nextDouble() * 1.5)
        randomLfo.amplitude.set(1.0)
        
        // Random LFO -> scaled by depth -> adds to main bend
        randomLfo.output.connect(randomDepthGain.inputA)
        randomDepthGain.inputB.set(0.0)
        
        // Non-linear shaping
        bendInputProxy.output.connect(nonlinearMixer.inputA)
        nonlinearMixer.inputB.set(1.5)
        nonlinearMixer.inputC.set(0.0)
        
        // Mix: nonlinear shaped bend + random modulation
        nonlinearMixer.output.connect(randomMixer.inputA)
        randomDepthGain.output.connect(randomMixer.inputB)
        
        // Final pitch bend output
        randomMixer.output.connect(bendDepthGain.inputA)
        bendDepthGain.inputB.set(0.0)
        bendDepthGain.output.connect(pitchOutputProxy.input)
        
        // Timbre modulation
        bendInputProxy.output.connect(timbreModGain.inputA)
        timbreModGain.inputB.set(0.0)
        timbreModGain.output.connect(timbreOutputProxy.input)
        
        // Monitor for UI feedback
        pitchOutputProxy.output.connect(bendMonitor.input)
        bendMonitor.setHalfLife(0.016)
        
        // ═══════════════════════════════════════════════════════════
        // TENSION SOUND WIRING
        // Very subtle low-mid frequency that rises with bend
        // ═══════════════════════════════════════════════════════════
        tensionOsc.frequency.set(300.0) // Start at 300Hz (much lower than before)
        tensionOsc.amplitude.set(0.1) // Very quiet (was 0.5)
        
        // Tension envelope: smooth attack/release
        tensionEnvelope.setAttack(0.1) // Slower attack to fade in
        tensionEnvelope.setDecay(0.1)
        tensionEnvelope.setSustain(0.6)
        tensionEnvelope.setRelease(0.2)
        
        // Wire tension path
        tensionOsc.output.connect(tensionVca.inputA)
        tensionEnvelope.output.connect(tensionVca.inputB)
        tensionVca.output.connect(tensionGain.inputA)
        tensionGain.inputB.set(0.0) // Will be set by bend amount
        
        // ═══════════════════════════════════════════════════════════
        // SPRING SOUND WIRING WITH WOBBLE
        // Creates a "boing-boing-boing" effect with decaying bounces
        // ═══════════════════════════════════════════════════════════
        springOsc.amplitude.set(0.2) // Moderate volume
        
        // Wobble LFO - oscillates at ~8Hz to create the "bounce" effect
        // This makes the frequency go up and down rapidly as the spring rings
        wobbleLfo.frequency.set(8.0) // 8Hz wobble rate
        wobbleLfo.amplitude.set(1.0)
        
        // Wobble depth - modulated by envelope so wobble fades out
        wobbleLfo.output.connect(wobbleDepth.inputA)
        wobbleDepth.inputB.set(80.0) // ±80Hz wobble (will be scaled by envelope)
        
        // Base frequency sweep: starts at 500Hz, sweeps down
        springFreqBase.inputB.set(-150.0) // Sweep down 150Hz
        springFreqBase.inputC.set(350.0)  // End at 350Hz
        
        // Mix base frequency + wobble -> spring oscillator
        springFreqBase.output.connect(wobbleMixer.inputA)
        wobbleDepth.output.connect(wobbleMixer.inputB)
        wobbleMixer.output.connect(springOsc.frequency)
        
        // Spring envelope: longer decay for multiple wobbles
        springEnvelope.setAttack(0.003)
        springEnvelope.setDecay(0.4) // Longer decay = more wobbles audible
        springEnvelope.setSustain(0.0)
        springEnvelope.setRelease(0.3)
        
        // Envelope controls both VCA and the frequency sweep
        springEnvelope.output.connect(springVca.inputB)
        springEnvelope.output.connect(springFreqBase.inputA)
        
        // Wire spring oscillator through VCA
        springOsc.output.connect(springVca.inputA)
        
        // Final gain control
        springVca.output.connect(springGain.inputA)
        springGain.inputB.set(0.4) // 40% overall spring volume
        
        // Mix tension + spring sounds
        tensionGain.output.connect(audioMixer.inputA)
        springGain.output.connect(audioMixer.inputB)
        audioMixer.output.connect(audioOutputProxy.input)
    }

    /**
     * Set the bend amount.
     * @param amount Value from -1 (full down) to +1 (full up), 0 = center/neutral
     */
    fun setBend(amount: Float) {
        val wasActive = _bendAmount.absoluteValue > 0.05f
        _bendAmount = amount.coerceIn(-1f, 1f)
        val isActive = _bendAmount.absoluteValue > 0.05f
        
        // Calculate pitch bend in Hz offset
        val normalizedBend = _bendAmount
        val tensionCurve = normalizedBend * (1.0 + normalizedBend.absoluteValue * 0.5)
        
        val semitones = tensionCurve * _maxBendSemitones
        val frequencyMultiplier = 2.0.pow(semitones / 12.0) - 1.0
        
        bendInputProxy.input.set(normalizedBend.toDouble())
        bendDepthGain.inputB.set(frequencyMultiplier)
        
        // Random depth increases with bend intensity
        val randomIntensity = _randomDepth * normalizedBend.absoluteValue * 0.1
        randomDepthGain.inputB.set(randomIntensity.toDouble())
        
        // Timbre modulation
        val timbreAmount = normalizedBend.absoluteValue * _timbreModulation
        timbreModGain.inputB.set(timbreAmount.toDouble())
        
        // Vary the random LFO rate based on bend intensity
        val lfoRate = 1.5 + (normalizedBend.absoluteValue * 3.0)
        randomLfo.frequency.set(lfoRate)
        
        // ═══════════════════════════════════════════════════════════
        // TENSION SOUND CONTROL
        // ═══════════════════════════════════════════════════════════
        
        // Tension frequency rises with bend amount (300Hz to 500Hz range)
        val tensionFreq = 300.0 + (normalizedBend.absoluteValue * 200.0)
        tensionOsc.frequency.set(tensionFreq)
        
        // Tension gain scales with bend intensity (very subtle - max 1.5%)
        val tensionLevel = normalizedBend.absoluteValue * 0.015
        tensionGain.inputB.set(tensionLevel.toDouble())
        
        // Trigger tension envelope when bend starts
        if (isActive && !wasActive) {
            tensionEnvelope.input.set(1.0)
            _wasActive = true
        } else if (!isActive && wasActive) {
            // Release tension envelope when bend ends
            tensionEnvelope.input.set(0.0)
        }
        
        // Trigger spring sound when returning to center after being bent
        if (!isActive && _wasActive) {
            // Wobbling "boing" - trigger the spring envelope
            springEnvelope.input.set(1.0)
            // Immediately release (envelope handles the decay)
            springEnvelope.input.set(0.0)
            _wasActive = false
        }
    }

    /**
     * Get current bend amount.
     */
    fun getBend(): Float = _bendAmount
    
    /**
     * Set maximum bend range in semitones (default 24 = 2 octaves).
     */
    fun setMaxBendSemitones(semitones: Float) {
        _maxBendSemitones = semitones.coerceIn(1f, 48f)
    }
    
    /**
     * Set random detuning depth (0-1, default 0.1).
     */
    fun setRandomDepth(depth: Float) {
        _randomDepth = depth.coerceIn(0f, 1f)
    }
    
    /**
     * Set timbre modulation amount (0-1, default 0.3).
     */
    fun setTimbreModulation(amount: Float) {
        _timbreModulation = amount.coerceIn(0f, 1f)
    }
    
    /**
     * Set spring sound volume (0-1, default 0.4).
     */
    fun setSpringVolume(volume: Float) {
        springGain.inputB.set(volume.toDouble().coerceIn(0.0, 1.0))
    }
    
    /**
     * Set tension sound volume (0-1, default 0.015).
     */
    fun setTensionVolume(volume: Float) {
        // This sets the max tension level
        tensionGain.inputB.set(volume.toDouble().coerceIn(0.0, 0.1))
    }

    /**
     * Get current bend output value for visualization.
     */
    fun getCurrentValue(): Float = bendMonitor.getCurrent().toFloat().coerceIn(-1f, 1f)
}
