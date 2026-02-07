package org.balch.orpheus.core.audio.dsp

/**
 * Factory for creating DSP units.
 * Aggregates individual unit factories for easier injection.
 */
interface DspFactory {
    fun createSineOscillator(): SineOscillator
    fun createTriangleOscillator(): TriangleOscillator
    fun createSquareOscillator(): SquareOscillator
    fun createSawtoothOscillator(): SawtoothOscillator
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
    fun createPlaitsUnit(): PlaitsUnit
    fun createDrumUnit(): DrumUnit
    fun createResonatorUnit(): ResonatorUnit
    fun createGrainsUnit(): GrainsUnit
    fun createLooperUnit(): LooperUnit
    fun createWarpsUnit(): WarpsUnit
    fun createClockUnit(): ClockUnit
    fun createFluxUnit(): FluxUnit
}
