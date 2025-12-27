package org.balch.orpheus.core.audio.dsp

import com.jsyn.unitgen.EnvelopeDAHDSR
import com.jsyn.unitgen.InterpolatingDelay
import org.balch.orpheus.core.audio.TanhLimiter
import com.jsyn.unitgen.PeakFollower as JsynPeakFollower

/**
 * JVM actual implementations of DSP units.
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
            // This is linear search, fine for baking 1024 points once
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
        
        // JSyn looping support via queueLoop
        // PingPong not supported natively by FloatSample/Reader looping? 
        // Reader supports looping start/end indices.
        // For PingPong, we'd need to bake a ping-pong buffer or manage queue manually.
        // For now, map PingPong to Loop to save complexity or bake it?
        // Baking PingPong: Create buffer 2x size, forward then backward.
        // But setPath doesn't know mode yet?
        // Actually setPath knows mode if we call setMode first.
        // But usually mode can change.
        // Simplest: just use Loop for PingPong for now (limitation), or queue Once forward, Once backward?
        // Queueing: queue(s), queue(s_reversed) -> Loop?
        // queueLoop loops a specific segment.
        // Let's stick to simple Loop for 1 and 2.
        
        when (mode) {
            0 -> reader.dataQueue.queue(s)
            else -> reader.dataQueue.queueLoop(s, 0, BUFFER_SIZE)
        }
        
        // Auto-start if not running
        reader.start() 
    }

    override fun stop() {
        reader.dataQueue.clear()
        // reader.stop() // Don't stop unit, just clear queue so it outputs 0 (or holds last?)
        // VariableRateMonoReader holds last value? No, it usually stops or goes to 0?
        // Actually we want it to stop outputting.
        // Ideally we'd gate it. But clearing queue is a start.
    }

    override fun reset() {
        reader.dataQueue.clear()
    }
}
