package org.balch.orpheus.core.audio.dsp

import com.jsyn.unitgen.EnvelopeDAHDSR
import com.jsyn.unitgen.InterpolatingDelay
import org.balch.orpheus.core.audio.TanhLimiter
import com.jsyn.unitgen.PeakFollower as JsynPeakFollower

/**
 * Android actual implementations of DSP units using JSyn.
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

class JsynEnvelope : Envelope {
    internal val jsEnv = EnvelopeDAHDSR()

    override val input: AudioInput = JsynAudioInput(jsEnv.input)
    override val output: AudioOutput = JsynAudioOutput(jsEnv.output)

    override fun setAttack(seconds: Double) {
        jsEnv.attack.set(seconds)
    }

    override fun setDecay(seconds: Double) {
        jsEnv.decay.set(seconds)
    }

    override fun setSustain(level: Double) {
        jsEnv.sustain.set(level)
    }

    override fun setRelease(seconds: Double) {
        jsEnv.release.set(seconds)
    }
}

actual interface DelayLine : AudioUnit {
    actual val input: AudioInput
    actual val delay: AudioInput
    actual fun allocate(maxSamples: Int)
}

class JsynDelayLine : DelayLine {
    internal val jsDelay = InterpolatingDelay()

    override val input: AudioInput = JsynAudioInput(jsDelay.input)
    override val delay: AudioInput = JsynAudioInput(jsDelay.delay)
    override val output: AudioOutput = JsynAudioOutput(jsDelay.output)

    override fun allocate(maxSamples: Int) {
        jsDelay.allocate(maxSamples)
    }
}

actual interface PeakFollower : AudioUnit {
    actual val input: AudioInput
    actual fun setHalfLife(seconds: Double)
    actual fun getCurrent(): Double
}

class JsynPeakFollowerWrapper : PeakFollower {
    internal val jsPeak = JsynPeakFollower()

    override val input: AudioInput = JsynAudioInput(jsPeak.input)
    override val output: AudioOutput = JsynAudioOutput(jsPeak.output)

    override fun setHalfLife(seconds: Double) {
        jsPeak.halfLife.set(seconds)
    }

    override fun getCurrent(): Double {
        return jsPeak.output.get()
    }
}

actual interface Limiter : AudioUnit {
    actual val input: AudioInput
    actual val drive: AudioInput
}

class JsynLimiter : Limiter {
    internal val jsLimiter = TanhLimiter()

    override val input: AudioInput = JsynAudioInput(jsLimiter.input)
    override val drive: AudioInput = JsynAudioInput(jsLimiter.drive)
    override val output: AudioOutput = JsynAudioOutput(jsLimiter.output)
}

actual interface LinearRamp : AudioUnit {
    actual val input: AudioInput
    actual val time: AudioInput
}

class JsynLinearRampWrapper : LinearRamp {
    internal val jsRamp = com.jsyn.unitgen.LinearRamp()

    override val input: AudioInput = JsynAudioInput(jsRamp.input)
    override val time: AudioInput = JsynAudioInput(jsRamp.time)
    override val output: AudioOutput = JsynAudioOutput(jsRamp.output)
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

class JsynAutomationPlayer : AutomationPlayer {
    internal val reader = com.jsyn.unitgen.VariableRateMonoReader()
    private var sample: com.jsyn.data.FloatSample? = null
    
    // Resolution for baked buffer
    private val BUFFER_SIZE = 1024
    
    private var durationSeconds: Float = 1.0f
    private var mode: Int = 0 // 0=Once, 1=Loop, 2=PingPong

    override val output: AudioOutput = JsynAudioOutput(reader.output)

    override fun setPath(times: FloatArray, values: FloatArray, count: Int) {
        if (count < 2) {
            reader.dataQueue.clear()
            return
        }

        // Bake the path into a FloatSample
        val floatData = FloatArray(BUFFER_SIZE)
        
        // Helper to get value at time t (0..1)
        fun getValueAt(t: Float): Float {
            // Find segment
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
            floatData[i] = getValueAt(t)
        }

        val newSample = com.jsyn.data.FloatSample(floatData)
        this.sample = newSample
        
        // Update rate based on duration
        updateRate()
    }

    override fun setDuration(seconds: Float) {
        this.durationSeconds = seconds
        updateRate()
    }
    
    private fun updateRate() {
        if (durationSeconds > 0) {
            val rate = BUFFER_SIZE / durationSeconds
            reader.rate.set(rate.toDouble())
        }
    }

    override fun setMode(mode: Int) {
        this.mode = mode
    }

    override fun play() {
        val s = sample ?: return
        reader.dataQueue.clear()
        
        when (mode) {
            0 -> reader.dataQueue.queue(s)
            else -> reader.dataQueue.queueLoop(s, 0, BUFFER_SIZE)
        }
        
        reader.start() 
    }

    override fun stop() {
        reader.dataQueue.clear()
    }

    override fun reset() {
        reader.dataQueue.clear()
    }
}

actual interface LooperUnit : AudioUnit {
    actual val inputLeft: AudioInput
    actual val inputRight: AudioInput
    actual override val output: AudioOutput
    actual val outputRight: AudioOutput
    actual val recordGate: AudioInput
    actual val playGate: AudioInput
    actual fun setRecording(active: Boolean)
    actual fun setPlaying(active: Boolean)
    actual fun allocate(maxSeconds: Double)
    actual fun clear()
    actual fun getPosition(): Float
    actual fun getLoopDuration(): Double
}

class JsynLooperUnit : LooperUnit {
    private val SAMPLE_RATE = 44100.0
    private val CROSSFADE_MS = 20.0  // 20ms crossfade for smooth loop restart
    private val CROSSFADE_SAMPLES = (SAMPLE_RATE * CROSSFADE_MS / 1000.0).toInt()
    
    internal val writerLeft = com.jsyn.unitgen.FixedRateMonoWriter()
    internal val writerRight = com.jsyn.unitgen.FixedRateMonoWriter()
    internal val readerLeft = com.jsyn.unitgen.VariableRateMonoReader()
    internal val readerRight = com.jsyn.unitgen.VariableRateMonoReader()
    
    private val bufferLeft = com.jsyn.data.FloatSample()
    private val bufferRight = com.jsyn.data.FloatSample()
    
    // Gates (Mono control for now)
    internal val recordGateInput = com.jsyn.unitgen.PassThrough()
    internal val playGateInput = com.jsyn.unitgen.PassThrough()
    
    // Connect writer input
    override val inputLeft: AudioInput = JsynAudioInput(writerLeft.input)
    override val inputRight: AudioInput = JsynAudioInput(writerRight.input)
    
    // Connect reader output
    override val output: AudioOutput = JsynAudioOutput(readerLeft.output)
    override val outputRight: AudioOutput = JsynAudioOutput(readerRight.output)
    
    // Gates
    override val recordGate: AudioInput = JsynAudioInput(recordGateInput.input)
    override val playGate: AudioInput = JsynAudioInput(playGateInput.input)

    private var loopSampleCount: Int = 0  // Exact number of samples recorded
    private var recordStartFrame: Long = 0
    private var playbackStartFrame: Long = 0
    private var isRecording: Boolean = false
    private var isPlaying: Boolean = false

    init {
        // Set reader rate to sample rate - CRITICAL for playback!
        readerLeft.rate.set(SAMPLE_RATE)
        readerRight.rate.set(SAMPLE_RATE)
        // Note: Units are naturally in stopped state when created.
        // Don't call stop() here as units aren't yet added to a synthesizer.
    }
    
    /**
     * Apply a crossfade between the end and start of the loop to eliminate clicks.
     * The end of the loop fades out while the start fades in, creating a seamless blend.
     */
    private fun applyCrossfade(buffer: com.jsyn.data.FloatSample, sampleCount: Int) {
        if (sampleCount < CROSSFADE_SAMPLES * 2) return  // Loop too short for crossfade
        
        val fadeLength = CROSSFADE_SAMPLES.coerceAtMost(sampleCount / 4)  // Max 25% of loop
        
        // Read the relevant portions into temporary arrays
        val startData = FloatArray(fadeLength)
        val endData = FloatArray(fadeLength)
        
        buffer.read(0, startData, 0, fadeLength)
        buffer.read(sampleCount - fadeLength, endData, 0, fadeLength)
        
        // Apply crossfade: blend start and end
        for (i in 0 until fadeLength) {
            val fadeIn = i.toFloat() / fadeLength   // 0.0 -> 1.0
            val fadeOut = 1.0f - fadeIn              // 1.0 -> 0.0
            
            // Blend: fade in the start, fade out the end
            val blendedStart = startData[i] * fadeIn + endData[i] * fadeOut
            val blendedEnd = endData[i] * fadeOut + startData[i] * fadeIn
            
            startData[i] = blendedStart
            endData[i] = blendedEnd
        }
        
        // Write the crossfaded data back
        buffer.write(0, startData, 0, fadeLength)
        buffer.write(sampleCount - fadeLength, endData, 0, fadeLength)
    }

    override fun setRecording(active: Boolean) {
        if (active == isRecording) return
        isRecording = active
        
        if (active) {
            // Stop any current playback first
            if (isPlaying) {
                isPlaying = false
                readerLeft.dataQueue.clear()
                readerRight.dataQueue.clear()
            }
            
            // Start recording - track frame count for exact timing
            recordStartFrame = writerLeft.synthesisEngine.frameCount
            writerLeft.dataQueue.clear()
            writerRight.dataQueue.clear()
            writerLeft.dataQueue.queue(bufferLeft) // Record into buffer once
            writerRight.dataQueue.queue(bufferRight)
            writerLeft.start()
            writerRight.start()
        } else {
            // Stop recording - calculate exact sample count
            writerLeft.stop()
            writerRight.stop()
            val endFrame = writerLeft.synthesisEngine.frameCount
            loopSampleCount = (endFrame - recordStartFrame).toInt().coerceAtMost(bufferLeft.numFrames)
            
            // Apply crossfade for seamless looping
            if (loopSampleCount > 0) {
                applyCrossfade(bufferLeft, loopSampleCount)
                applyCrossfade(bufferRight, loopSampleCount)
                setPlaying(true)
            }
        }
    }

    override fun setPlaying(active: Boolean) {
        // Avoid redundant calls
        if (active == isPlaying) return
        
        isPlaying = active
        
        if (active && loopSampleCount > 0) {
            playbackStartFrame = readerLeft.synthesisEngine.frameCount
            readerLeft.dataQueue.clear()
            readerRight.dataQueue.clear()
            // Queue the exact recorded samples for looping
            readerLeft.dataQueue.queueLoop(bufferLeft, 0, loopSampleCount)
            readerRight.dataQueue.queueLoop(bufferRight, 0, loopSampleCount)
            readerLeft.start()
            readerRight.start()
        } else {
            // Stop playback - clear queue and stop readers to prevent noise
            readerLeft.dataQueue.clear()
            readerRight.dataQueue.clear()
            readerLeft.stop()
            readerRight.stop()
        }
    }

    override fun allocate(maxSeconds: Double) {
        val frames = (44100 * maxSeconds).toInt()
        bufferLeft.allocate(frames, 1)
        bufferRight.allocate(frames, 1)
    }
    
    override fun clear() {
        // Stop everything first
        if (isRecording) {
            writerLeft.stop()
            writerRight.stop()
            isRecording = false
        }
        if (isPlaying) {
            readerLeft.dataQueue.clear()
            readerRight.dataQueue.clear()
            readerLeft.stop()
            readerRight.stop()
            isPlaying = false
        }
        loopSampleCount = 0
    }
    
    override fun getPosition(): Float {
        if (isRecording) {
            val recordedFrames = (writerLeft.synthesisEngine.frameCount - recordStartFrame).toInt()
            val maxFrames = bufferLeft.numFrames
            return (recordedFrames.toFloat() / maxFrames).coerceIn(0f, 1f)
        }
        if (isPlaying && loopSampleCount > 0) {
            val playedFrames = (readerLeft.synthesisEngine.frameCount - playbackStartFrame).toInt()
            val posInLoop = playedFrames % loopSampleCount
            return posInLoop.toFloat() / loopSampleCount
        }
        return 0f 
    }
    
    override fun getLoopDuration(): Double = loopSampleCount / SAMPLE_RATE
}

actual interface ClockUnit : AudioUnit {
    actual val frequency: AudioInput
    actual val pulseWidth: AudioInput
    actual override val output: AudioOutput
}

class JsynClockUnit : ClockUnit {
    internal val jsOsc = com.jsyn.unitgen.PulseOscillator()
    internal val scaler = com.jsyn.unitgen.MultiplyAdd()

    init {
        // PulseOsc output is -1 to 1. We want 0 to 1 for logic triggers.
        // Scale by 0.5 -> -0.5 to 0.5
        // Add 0.5 -> 0.0 to 1.0
        scaler.inputB.set(0.5)
        scaler.inputC.set(0.5)
        jsOsc.output.connect(scaler.inputA)

        // Default amplitude 1.0 (full range -1 to 1 before scaling)
        jsOsc.amplitude.set(1.0)
        // Default pulse width (50% duty cycle)
        jsOsc.width.set(0.5)
    }

    override val frequency: AudioInput = JsynAudioInput(jsOsc.frequency)
    override val pulseWidth: AudioInput = JsynAudioInput(jsOsc.width)
    override val output: AudioOutput = JsynAudioOutput(scaler.output)
}
