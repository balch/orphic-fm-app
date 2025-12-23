package org.balch.songe.core.audio.dsp

/**
 * Waveform types for oscillators.
 */
enum class WaveformType {
    SINE,
    TRIANGLE,
    SQUARE,
    SAWTOOTH
}

/**
 * Audio oscillator that generates periodic waveforms.
 */
expect interface Oscillator : AudioUnit {
    /** Frequency input in Hz */
    val frequency: AudioInput

    /** Amplitude input (0.0 - 1.0 typically) */
    val amplitude: AudioInput
}

/**
 * DAHDSR Envelope generator.
 */
expect interface Envelope : AudioUnit {
    /** Gate input (>0 = on, 0 = off) */
    val input: AudioInput

    /** Set attack time in seconds */
    fun setAttack(seconds: Double)

    /** Set decay time in seconds */
    fun setDecay(seconds: Double)

    /** Set sustain level (0.0 - 1.0) */
    fun setSustain(level: Double)

    /** Set release time in seconds */
    fun setRelease(seconds: Double)
}

/**
 * Delay line for audio signals.
 */
expect interface DelayLine : AudioUnit {
    /** Audio input */
    val input: AudioInput

    /** Delay time input in seconds */
    val delay: AudioInput

    /** Allocate buffer for maximum delay in samples */
    fun allocate(maxSamples: Int)
}

/**
 * Peak follower / envelope follower.
 */
expect interface PeakFollower : AudioUnit {
    /** Audio input to track */
    val input: AudioInput

    /** Set half-life decay time in seconds */
    fun setHalfLife(seconds: Double)

    /** Get current peak value */
    fun getCurrent(): Double
}

/**
 * Soft limiter with drive control.
 */
expect interface Limiter : AudioUnit {
    /** Audio input */
    val input: AudioInput

    /** Drive amount input */
    val drive: AudioInput
}
