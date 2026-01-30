package org.balch.orpheus.core.audio.dsp

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
    fun createMinimum(): Minimum
    fun createMaximum(): Maximum
    fun createLinearRamp(): LinearRamp
    fun createAutomationPlayer(): AutomationPlayer
    fun createDrumUnit(): DrumUnit
    fun createResonatorUnit(): ResonatorUnit
    fun createGrainsUnit(): GrainsUnit
    fun createLooperUnit(): LooperUnit
    fun createWarpsUnit(): WarpsUnit
    fun createClockUnit(): ClockUnit

    /** Master output - connect final audio here */
    val lineOutLeft: AudioInput
    val lineOutRight: AudioInput

    // Monitoring
    /** Get current CPU load (0.0 - 1.0) */
    fun getCpuLoad(): Float

    /** Get current audio time in seconds */
    fun getCurrentTime(): Double
}

