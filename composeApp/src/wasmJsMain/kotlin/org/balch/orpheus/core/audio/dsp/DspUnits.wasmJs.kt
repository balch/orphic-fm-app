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
    
    override val input: AudioInput = object : AudioInput {
        override fun set(value: Double) {
            val wasOpen = lastGateValue > 0.5
            val isOpen = value > 0.5
            lastGateValue = value
            
            if (isOpen && !wasOpen) {
                triggerAttack()
            } else if (!isOpen && wasOpen) {
                triggerRelease()
            }
        }
        
        override fun disconnectAll() {
            // Gate input doesn't have audio connections
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
    
    // Buffer for time domain data - use Float32Array for Web Audio API
    private val dataArray = Float32Array(2048)
    
    override val input: AudioInput = WebAudioNodeInput(analyser, 0, context)
    
    // PeakFollower output is passthrough (maintains signal flow)
    private val passThrough = context.createGain().also { it.gain.value = 1f }
    override val output: AudioOutput = WebAudioNodeOutput(passThrough)
    
    // Manual peak tracking with exponential decay
    private var currentPeak = 0.0
    private var halfLifeSeconds = 0.1
    
    init {
        // Route input through to output
        analyser.connect(passThrough)
    }
    
    override fun setHalfLife(seconds: Double) {
        halfLifeSeconds = seconds.coerceAtLeast(0.001)
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
    
    init {
        // Connect drive gain -> waveshaper
        driveGain.connect(shaper)
        
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
    }
    
    override val input: AudioInput = WebAudioNodeInput(driveGain, 0, context)
    
    override val drive: AudioInput = object : AudioInput {
        override fun set(value: Double) {
            // Map drive 0-1 to gain multiplier
            val driveAmount = (1.0 + value * 4.0).toFloat()
            driveGain.gain.value = driveAmount
            updateCurve(driveAmount)
        }
        
        override fun disconnectAll() {
            // Drive is typically a constant, not modulated
        }
    }
    
    override val output: AudioOutput = WebAudioNodeOutput(shaper)
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

    override val input: AudioInput = object : AudioInput {
        override fun set(value: Double) {
             val now = context.currentTime
             source.offset.cancelScheduledValues(now)
             source.offset.setValueAtTime(source.offset.value, now)
             source.offset.linearRampToValueAtTime(value.toFloat(), now + rampTime)
        }
        override fun disconnectAll() {}
    }
    
    override val time: AudioInput = object : AudioInput {
        override fun set(value: Double) {
            rampTime = value.coerceAtLeast(0.001)
        }
        override fun disconnectAll() {}
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
    
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    
    override fun setPath(times: FloatArray, values: FloatArray, count: Int) {
         if (count < 2) return
         
         val newBuffer = context.createBuffer(1, BUFFER_SIZE, context.sampleRate.toFloat())
         val channelData = newBuffer.getChannelData(0)
         
        fun getValueAt(t: Float): Float {
            if (t <= times[0]) return values[0]
            if (t >= times[count - 1]) return values[count - 1]
            
            for (i in 0 until count - 1) {
                if (t >= times[i] && t <= times[i+1]) {
                    val t1 = times[i]
                    val t2 = times[i+1]
                    val v1 = values[i]
                    val v2 = values[i+1]
                    if (t2 == t1) return v1
                    val fraction = (t - t1) / (t2 - t1)
                    return v1 + fraction * (v2 - v1)
                }
            }
            return values[count - 1]
        }
         
         for (i in 0 until BUFFER_SIZE) {
             val t = i.toFloat() / (BUFFER_SIZE - 1)
             channelData[i] = getValueAt(t)
         }
         
         this.buffer = newBuffer
    }
    
    override fun setDuration(seconds: Float) {
        this.durationSeconds = seconds
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
