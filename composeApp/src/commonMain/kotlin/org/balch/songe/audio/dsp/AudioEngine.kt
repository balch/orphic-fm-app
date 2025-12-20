package org.balch.songe.audio.dsp

/**
 * Platform-specific audio engine that provides DSP unit creation and audio output.
 */
expect class AudioEngine() {
    /** Start audio processing */
    fun start()
    
    /** Stop audio processing */
    fun stop()
    
    /** Check if engine is running */
    val isRunning: Boolean
    
    /** Sample rate in Hz */
    val sampleRate: Int
    
    /** Add a unit to the synthesis graph */
    fun addUnit(unit: AudioUnit)
    
    // Unit factories
    fun createSineOscillator(): SineOscillator
    fun createTriangleOscillator(): TriangleOscillator
    fun createSquareOscillator(): SquareOscillator
    fun createEnvelope(): Envelope
    fun createDelayLine(): DelayLine
    fun createPeakFollower(): PeakFollower
    fun createLimiter(): Limiter
    fun createMultiply(): Multiply
    fun createAdd(): Add
    fun createMultiplyAdd(): MultiplyAdd
    fun createPassThrough(): PassThrough
    
    /** Master output - connect final audio here */
    val lineOutLeft: AudioInput
    val lineOutRight: AudioInput
}
