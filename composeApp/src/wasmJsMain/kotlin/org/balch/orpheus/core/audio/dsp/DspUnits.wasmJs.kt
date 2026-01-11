package org.balch.orpheus.core.audio.dsp

import org.khronos.webgl.Float32Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * WASM actual implementations of DSP units using Web Audio API.
 */

actual interface Oscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

actual interface Envelope : AudioUnit {
    actual val input: AudioInput
    actual fun setAttack(seconds: Double)
    actual fun setDecay(seconds: Double)
    actual fun setSustain(level: Double)
    actual fun setRelease(seconds: Double)
}

/**
 * Envelope using a GainNode with scheduled automation.
 * The input acts as a gate signal (>0 = triggered).
 */
class WebAudioEnvelope(private val context: AudioContext) : Envelope {
    private val gainNode = context.createGain().also { it.gain.value = 0f }
    
    // Store ADSR values
    private var attackTime = 0.01
    private var decayTime = 0.1
    private var sustainLevel = 0.7
    private var releaseTime = 0.3
    
    // Gate state
    private var isGateOpen = false
    
    // Input is tracked manually for gate detection
    private var lastGateValue = 0.0
    
    // Constant source to feed the envelope gain node
    private val constantSource = context.createConstantSource().also {
        it.offset.value = 1.0f
        it.connect(gainNode)
        it.start()
    }
    
    override val input: AudioInput = WebAudioManualInput(context) { value ->
        val wasOpen = lastGateValue > 0.5
        val isOpen = value > 0.5
        lastGateValue = value
        
        if (isOpen && !wasOpen) {
            triggerAttack()
        } else if (!isOpen && wasOpen) {
            triggerRelease()
        }
    }
    
    override val output: AudioOutput = WebAudioNodeOutput(gainNode)
    
    private fun triggerAttack() {
        isGateOpen = true
        val now = context.currentTime
        gainNode.gain.cancelScheduledValues(now)
        gainNode.gain.setValueAtTime(gainNode.gain.value, now)
        
        // Attack phase
        gainNode.gain.linearRampToValueAtTime(1.0f, now + attackTime)
        
        // Decay to sustain
        gainNode.gain.linearRampToValueAtTime(
            sustainLevel.toFloat(),
            now + attackTime + decayTime
        )
    }
    
    private fun triggerRelease() {
        isGateOpen = false
        val now = context.currentTime
        gainNode.gain.cancelScheduledValues(now)
        gainNode.gain.setValueAtTime(gainNode.gain.value, now)
        
        // Release to zero (use small value to avoid log(0))
        gainNode.gain.linearRampToValueAtTime(0.0001f, now + releaseTime)
        gainNode.gain.linearRampToValueAtTime(0f, now + releaseTime + 0.001)
    }
    
    override fun setAttack(seconds: Double) {
        attackTime = seconds.coerceAtLeast(0.001)
    }
    
    override fun setDecay(seconds: Double) {
        decayTime = seconds.coerceAtLeast(0.001)
    }
    
    override fun setSustain(level: Double) {
        sustainLevel = level.coerceIn(0.0, 1.0)
    }
    
    override fun setRelease(seconds: Double) {
        releaseTime = seconds.coerceAtLeast(0.001)
    }
    
    /** Get the underlying GainNode for routing audio through the envelope */
    val webAudioNode: GainNode get() = gainNode
}

actual interface DelayLine : AudioUnit {
    actual val input: AudioInput
    actual val delay: AudioInput
    actual fun allocate(maxSamples: Int)
}

class WebAudioDelayLine(private val context: AudioContext) : DelayLine {
    // Default to 2 seconds max delay
    private var delayNode = context.createDelay(2.0)
    
    override val input: AudioInput = WebAudioNodeInput(delayNode, 0, context)
    override val delay: AudioInput = WebAudioParamInput(delayNode.delayTime, context)
    override val output: AudioOutput = WebAudioNodeOutput(delayNode)
    
    override fun allocate(maxSamples: Int) {
        // Web Audio DelayNode doesn't need pre-allocation
        // Max delay is set at construction time
        // For very long delays, we'd need to recreate the node
        val maxSeconds = maxSamples.toDouble() / context.sampleRate
        if (maxSeconds > 2.0) {
            // Recreate with larger buffer
            delayNode = context.createDelay(maxSeconds)
        }
    }
}

actual interface PeakFollower : AudioUnit {
    actual val input: AudioInput
    actual fun setHalfLife(seconds: Double)
    actual fun getCurrent(): Double
}

class WebAudioPeakFollower(private val context: AudioContext) : PeakFollower {
    private val analyser = context.createAnalyser().also {
        it.fftSize = 2048 // Larger buffer for more accurate peak detection
        it.smoothingTimeConstant = 0f // No smoothing, we'll do it manually
        it.minDecibels = -100f
        it.maxDecibels = 0f
    }
    
    // Envelope path for audio-rate modulation
    private val absShaper = context.createWaveShaper().apply {
        val samples = 1024
        val curve = Float32Array(samples)
        for (i in 0 until samples) {
            val x = (i.toFloat() / (samples - 1)) * 2 - 1  // -1 to 1
            curve[i] = abs(x).toFloat()
        }
        this.curve = curve
    }
    
    private val followerFilter = context.createBiquadFilter().apply {
        type = "lowpass"
        frequency.value = 10f // Default cutoff (~100ms response)
    }

    // Buffer for time domain data - use Float32Array for Web Audio API
    private val dataArray = Float32Array(2048)
    
    override val input: AudioInput = WebAudioNodeInput(analyser, 0, context)
    
    // PeakFollower output is the envelope (peak level)
    override val output: AudioOutput = WebAudioNodeOutput(followerFilter)
    
    // Manual peak tracking with exponential decay for UI
    private var currentPeak = 0.0
    private var halfLifeSeconds = 0.1
    
    init {
        // Wire envelope path
        analyser.connect(absShaper)
        absShaper.connect(followerFilter)
    }
    
    override fun setHalfLife(seconds: Double) {
        halfLifeSeconds = seconds.coerceAtLeast(0.001)
        
        // Map half-life to lowpass cutoff for the audio-rate output
        // fc = 1 / (2 * pi * tau), where tau = halfLife / ln(2)
        val tau = halfLifeSeconds / 0.69314718056
        val cutoff = 1.0 / (2.0 * 3.14159265359 * tau)
        followerFilter.frequency.value = cutoff.toFloat().coerceIn(0.1f, 1000f)
    }
    
    override fun getCurrent(): Double {
        analyser.getFloatTimeDomainData(dataArray)
        
        // Find peak absolute value in current buffer
        var instantPeak = 0f
        for (i in 0 until dataArray.length) {
            val absSample = abs(dataArray[i])
            if (absSample > instantPeak) {
                instantPeak = absSample
            }
        }
        
        // Exponential smoothing with attack/release behavior
        // Fast attack (immediately jump to higher values)
        // Slow release (decay based on half-life)
        if (instantPeak > currentPeak) {
            // Attack: quickly follow increases
            currentPeak = instantPeak.toDouble()
        } else {
            // Release: exponential decay
            // Calculate decay factor: after halfLife seconds, value should be 50%
            // Using a simple approximation: decay ~3% per 33ms frame for 0.1s half-life
            val bufferDuration = dataArray.length.toDouble() / context.sampleRate
            val decayFactor = 0.5.pow(bufferDuration / halfLifeSeconds)
            currentPeak *= decayFactor
            
            // But don't decay below the current instant peak
            if (currentPeak < instantPeak) {
                currentPeak = instantPeak.toDouble()
            }
        }
        
        return currentPeak.coerceIn(0.0, 1.0)
    }
}

actual interface Limiter : AudioUnit {
    actual val input: AudioInput
    actual val drive: AudioInput
}

class WebAudioLimiter(private val context: AudioContext) : Limiter {
    private val shaper = context.createWaveShaper()
    private val driveGain = context.createGain().also { it.gain.value = 1f }
    private val postGain = context.createGain().also { it.gain.value = 1f }
    
    init {
        // Connect drive gain -> waveshaper -> postGain
        driveGain.connect(shaper)
        shaper.connect(postGain)
        
        // Create tanh curve for soft limiting
        updateCurve(1.0f)
    }
    
    private fun updateCurve(drive: Float) {
        val samples = 1024
        val curve = Float32Array(samples)
        for (i in 0 until samples) {
            val x = (i.toFloat() / (samples - 1)) * 2 - 1  // -1 to 1
            curve[i] = tanh(x * drive).toFloat()
        }
        shaper.curve = curve
        shaper.oversample = "2x"

        // Volume compensation like in JVM TanhLimiter
        val drv = drive.toDouble().coerceIn(1.0, 50.0)
        val compensation = 1.0 / tanh(drv.coerceAtMost(3.0))
        postGain.gain.value = compensation.coerceAtMost(1.5).toFloat()
    }
    
    override val input: AudioInput = WebAudioNodeInput(driveGain, 0, context)
    
    override val drive: AudioInput = WebAudioManualInput(context) { value ->
        // value is already mapped by DspDistortionPlugin (typically 1..15)
        val driveAmount = value.toFloat()
        // driveGain should stay 1.0 to let shaper curve handle the drive
        updateCurve(driveAmount)
    }
    
    override val output: AudioOutput = WebAudioNodeOutput(postGain)
}

actual interface LinearRamp : AudioUnit {
    actual val input: AudioInput
    actual val time: AudioInput
}

class WebAudioLinearRamp(private val context: AudioContext) : LinearRamp {
    private val source = context.createConstantSource().also {
        it.offset.value = 0f
        it.start()
    }
    
    private var rampTime = 0.02

    override val input: AudioInput = WebAudioManualInput(context) { value ->
         val now = context.currentTime
         source.offset.cancelScheduledValues(now)
         source.offset.setValueAtTime(source.offset.value, now)
         source.offset.linearRampToValueAtTime(value.toFloat(), now + rampTime)
    }
    
    override val time: AudioInput = WebAudioManualInput(context) { value ->
        rampTime = value.coerceAtLeast(0.001)
    }
    
    override val output: AudioOutput = WebAudioNodeOutput(source)
}

actual interface AutomationPlayer : AudioUnit {
    actual override val output: AudioOutput
    actual fun setPath(times: FloatArray, values: FloatArray, count: Int)
    actual fun setDuration(seconds: Float)
    actual fun setMode(mode: Int)
    actual fun play()
    actual fun stop()
    actual fun reset()
}

class WebAudioAutomationPlayer(private val context: AudioContext) : AutomationPlayer {
    private val outputGain = context.createGain()
    private var sourceNode: AudioBufferSourceNode? = null
    private var buffer: AudioBuffer? = null
    
    private val BUFFER_SIZE = 1024
    private var durationSeconds = 1.0f
    private var mode = 0
    
    private var lastTimes: FloatArray? = null
    private var lastValues: FloatArray? = null
    private var lastCount: Int = 0

    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    
    override fun setPath(times: FloatArray, values: FloatArray, count: Int) {
         if (count < 2) return
         this.lastTimes = times.copyOf()
         this.lastValues = values.copyOf()
         this.lastCount = count
         
         if (durationSeconds > 0) {
             bake()
         }
    }
    
    private fun bake() {
        val times = lastTimes ?: return
        val values = lastValues ?: return
        val count = lastCount
        val duration = durationSeconds
        
        val newBuffer = context.createBuffer(1, BUFFER_SIZE, context.sampleRate.toFloat())
        val channelData = newBuffer.getChannelData(0)
        
        fun getValueAt(s: Float): Float {
            if (s <= times[0]) return values[0]
            if (s >= times[count - 1]) return values[count - 1]
            
            for (i in 0 until count - 1) {
                if (s >= times[i] && s <= times[i+1]) {
                    val t1 = times[i]
                    val t2 = times[i+1]
                    val v1 = values[i]
                    val v2 = values[i+1]
                    if (t2 == t1) return v1
                    val fraction = (s - t1) / (t2 - t1)
                    return v1 + fraction * (v2 - v1)
                }
            }
            return values[count - 1]
        }
         
         for (i in 0 until BUFFER_SIZE) {
             val s = (i.toFloat() / (BUFFER_SIZE - 1)) * duration
             channelData[i] = getValueAt(s)
         }
         
         this.buffer = newBuffer
    }
    
    override fun setDuration(seconds: Float) {
        this.durationSeconds = seconds
        if (lastTimes != null) {
            bake()
        }
        updateRate()
    }
    
    private fun updateRate() {
        sourceNode?.let { node ->
             val bufferDuration = BUFFER_SIZE.toFloat() / context.sampleRate
             val rate = if (durationSeconds > 0) bufferDuration / durationSeconds else 1f
             node.playbackRate.value = rate
        }
    }
    
    override fun setMode(mode: Int) {
        this.mode = mode
        sourceNode?.loop = (mode != 0)
    }
    
    override fun play() {
        stop() // Stop existing
        
        val buf = buffer ?: return
        val source = context.createBufferSource()
        source.buffer = buf
        source.loop = (mode != 0)
        
        val bufferDuration = BUFFER_SIZE.toFloat() / context.sampleRate
        val rate = if (durationSeconds > 0) bufferDuration / durationSeconds else 1f
        source.playbackRate.value = rate
        
        source.connect(outputGain)
        source.start()
        this.sourceNode = source
    }
    
    override fun stop() {
        try {
            sourceNode?.stop()
            sourceNode?.disconnect()
        } catch (e: Throwable) {
            // Ignore
        }
        sourceNode = null
    }
    
    override fun reset() {
        stop()
    }
}

actual interface DrumUnit : AudioUnit {
    actual fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    )
    
    actual fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    )

    actual fun trigger(type: Int, accent: Float)
}

/**
 * WASM implementation of DrumUnit.
 * 
 * This is a simplified stub - full analog drum synthesis is complex
 * and would require custom AudioWorklet processors for Web Audio.
 * For now, this provides a basic noise burst for hi-hats and 
 * oscillator-based sounds for kick/snare.
 */
class WebAudioDrumUnit(private val context: AudioContext) : DrumUnit {
    private val outputGain = context.createGain().also { it.gain.value = 0.5f }
    
    // Per-drum stored parameters
    private var bdF0 = 55f
    private var bdTone = 0.5f
    private var bdDecay = 0.5f
    private var bdP4 = 0.5f
    private var bdP5 = 0.5f
    
    private var sdF0 = 180f
    private var sdTone = 0.5f
    private var sdDecay = 0.5f
    private var sdP4 = 0.5f
    
    private var hhF0 = 400f
    private var hhTone = 0.5f
    private var hhDecay = 0.5f
    private var hhP4 = 0.5f
    
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    
    override fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    ) {
        setParameters(type, frequency, tone, decay, param4, param5)
        trigger(type, accent)
    }
    
    override fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    ) {
        when (type) {
            0 -> { // Bass Drum
                bdF0 = frequency
                bdTone = tone
                bdDecay = decay
                bdP4 = param4
                bdP5 = param5
            }
            1 -> { // Snare Drum
                sdF0 = frequency
                sdTone = tone
                sdDecay = decay
                sdP4 = param4
            }
            2 -> { // Hi-Hat
                hhF0 = frequency
                hhTone = tone
                hhDecay = decay
                hhP4 = param4
            }
        }
    }
    
    override fun trigger(type: Int, accent: Float) {
        val now = context.currentTime
        
        when (type) {
            0 -> triggerKick(now, accent)
            1 -> triggerSnare(now, accent)
            2 -> triggerHiHat(now, accent)
        }
    }
    
    private fun triggerKick(now: Double, accent: Float) {
        // Simple kick: sine oscillator with pitch and amplitude envelope
        val osc = context.createOscillator()
        osc.type = "sine"
        
        val gain = context.createGain()
        gain.gain.value = 0f
        
        osc.connect(gain)
        gain.connect(outputGain)
        
        // Pitch envelope (pitch drops quickly)
        val startFreq = bdF0 * (1f + bdP4 * 2f) // Attack FM amount affects start pitch
        osc.frequency.setValueAtTime(startFreq, now)
        osc.frequency.exponentialRampToValueAtTime(bdF0.coerceAtLeast(20f), now + 0.05)
        
        // Amplitude envelope
        val decayTime = 0.1 + bdDecay * 0.9
        gain.gain.setValueAtTime(accent * 0.8f, now)
        gain.gain.exponentialRampToValueAtTime(0.001f, now + decayTime)
        
        osc.start(now)
        osc.stop(now + decayTime + 0.01)
    }
    
    private fun triggerSnare(now: Double, accent: Float) {
        // Snare: oscillator + noise
        val osc = context.createOscillator()
        osc.type = "triangle"
        osc.frequency.value = sdF0
        
        val oscGain = context.createGain()
        oscGain.gain.value = 0f
        
        osc.connect(oscGain)
        oscGain.connect(outputGain)
        
        val decayTime = 0.05 + sdDecay * 0.3
        oscGain.gain.setValueAtTime(accent * 0.5f, now)
        oscGain.gain.exponentialRampToValueAtTime(0.001f, now + decayTime)
        
        osc.start(now)
        osc.stop(now + decayTime + 0.01)
        
        // Add noise component (simplified - would need AudioWorklet for proper noise)
        // For now, use a high-frequency oscillator as pseudo-noise
        val noise = context.createOscillator()
        noise.type = "sawtooth"
        noise.frequency.value = 5000f + sdP4 * 3000f
        
        val noiseGain = context.createGain()
        noiseGain.gain.value = 0f
        
        noise.connect(noiseGain)
        noiseGain.connect(outputGain)
        
        noiseGain.gain.setValueAtTime(accent * sdP4 * 0.3f, now)
        noiseGain.gain.exponentialRampToValueAtTime(0.001f, now + decayTime * 0.7)
        
        noise.start(now)
        noise.stop(now + decayTime)
    }
    
    private fun triggerHiHat(now: Double, accent: Float) {
        // Hi-hat: high frequency pseudo-noise
        val osc1 = context.createOscillator()
        osc1.type = "square"
        osc1.frequency.value = hhF0 * 10f
        
        val osc2 = context.createOscillator()
        osc2.type = "square"
        osc2.frequency.value = hhF0 * 10f * 1.414f // Slightly detuned
        
        val gain = context.createGain()
        gain.gain.value = 0f
        
        osc1.connect(gain)
        osc2.connect(gain)
        gain.connect(outputGain)
        
        val decayTime = 0.02 + hhDecay * 0.3
        gain.gain.setValueAtTime(accent * 0.3f, now)
        gain.gain.exponentialRampToValueAtTime(0.001f, now + decayTime)
        
        osc1.start(now)
        osc2.start(now)
        osc1.stop(now + decayTime + 0.01)
        osc2.stop(now + decayTime + 0.01)
    }
}

actual interface ResonatorUnit : AudioUnit {
    actual val input: AudioInput
    actual val auxOutput: AudioOutput
    
    actual fun setEnabled(enabled: Boolean)
    actual fun setMode(mode: Int)
    actual fun setStructure(value: Float)
    actual fun setBrightness(value: Float)
    actual fun setDamping(value: Float)
    actual fun setPosition(value: Float)
    actual fun strum(frequency: Float)
}

/**
 * WASM implementation of ResonatorUnit.
 * 
 * This is a simplified stub - full modal synthesis would require
 * custom AudioWorklet processors. For now, provides a basic
 * resonant filter implementation.
 */
class WebAudioResonatorUnit(private val context: AudioContext) : ResonatorUnit {
    private val inputGain = context.createGain().also { it.gain.value = 1f }
    private val outputGain = context.createGain().also { it.gain.value = 1f }
    private val auxGain = context.createGain().also { it.gain.value = 0f }
    
    // Simple resonant filter as placeholder
    private val filter = context.createBiquadFilter().also {
        it.type = "bandpass"
        it.frequency.value = 440f
        it.Q.value = 10f
    }
    
    private var enabled = false
    
    init {
        inputGain.connect(filter)
        filter.connect(outputGain)
        filter.connect(auxGain)
    }
    
    override val input: AudioInput = WebAudioNodeInput(inputGain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    override val auxOutput: AudioOutput = WebAudioNodeOutput(auxGain)
    
    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        outputGain.gain.value = if (enabled) 1f else 0f
    }
    
    override fun setMode(mode: Int) {
        // Mode affects filter type
        filter.type = when (mode) {
            0 -> "bandpass" // Modal
            1 -> "lowpass"  // String (comb filter not available in Web Audio)
            else -> "bandpass"
        }
    }
    
    override fun setStructure(value: Float) {
        // Structure affects Q / resonance spread
        filter.Q.value = 5f + value * 20f
    }
    
    override fun setBrightness(value: Float) {
        // Brightness affects filter frequency
        filter.frequency.value = 200f + value * 4000f
    }
    
    override fun setDamping(value: Float) {
        // Damping affects Q (lower Q = more damping)
        filter.Q.value = filter.Q.value * (1f - value * 0.5f)
    }
    
    override fun setPosition(value: Float) {
        // Position affects gain balance
        auxGain.gain.value = value * 0.5f
    }
    
    override fun strum(frequency: Float) {
        // Set filter to strum frequency and trigger
        filter.frequency.value = frequency
    }
}

