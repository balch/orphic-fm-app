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
