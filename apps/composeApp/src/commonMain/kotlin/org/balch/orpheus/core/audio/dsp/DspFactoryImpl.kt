package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspFactoryImpl @Inject constructor(
    private val sineFactory: SineOscillator.Factory,
    private val triangleFactory: TriangleOscillator.Factory,
    private val squareFactory: SquareOscillator.Factory,
    private val sawtoothFactory: SawtoothOscillator.Factory,
    private val envelopeFactory: Envelope.Factory,
    private val delayLineFactory: DelayLine.Factory,
    private val peakFollowerFactory: PeakFollower.Factory,
    private val limiterFactory: Limiter.Factory,
    private val multiplyFactory: Multiply.Factory,
    private val addFactory: Add.Factory,
    private val multiplyAddFactory: MultiplyAdd.Factory,
    private val passThroughFactory: PassThrough.Factory,
    private val minimumFactory: Minimum.Factory,
    private val maximumFactory: Maximum.Factory,
    private val linearRampFactory: LinearRamp.Factory,
    private val automationPlayerFactory: AutomationPlayer.Factory,
    private val plaitsUnitFactory: PlaitsUnit.Factory,
    private val drumUnitFactory: DrumUnit.Factory,
    private val resonatorUnitFactory: ResonatorUnit.Factory,
    private val grainsUnitFactory: GrainsUnit.Factory,
    private val looperUnitFactory: LooperUnit.Factory,
    private val warpsUnitFactory: WarpsUnit.Factory,
    private val clockUnitFactory: ClockUnit.Factory,
    private val fluxUnitFactory: FluxUnit.Factory
) : DspFactory {
    override fun createSineOscillator() = sineFactory.create()
    override fun createTriangleOscillator() = triangleFactory.create()
    override fun createSquareOscillator() = squareFactory.create()
    override fun createSawtoothOscillator() = sawtoothFactory.create()
    override fun createEnvelope() = envelopeFactory.create()
    override fun createDelayLine() = delayLineFactory.create()
    override fun createPeakFollower() = peakFollowerFactory.create()
    override fun createLimiter() = limiterFactory.create()
    override fun createMultiply() = multiplyFactory.create()
    override fun createAdd() = addFactory.create()
    override fun createMultiplyAdd() = multiplyAddFactory.create()
    override fun createPassThrough() = passThroughFactory.create()
    override fun createMinimum() = minimumFactory.create()
    override fun createMaximum() = maximumFactory.create()
    override fun createLinearRamp() = linearRampFactory.create()
    override fun createAutomationPlayer() = automationPlayerFactory.create()
    override fun createPlaitsUnit() = plaitsUnitFactory.create()
    override fun createDrumUnit() = drumUnitFactory.create()
    override fun createResonatorUnit() = resonatorUnitFactory.create()
    override fun createGrainsUnit() = grainsUnitFactory.create()
    override fun createLooperUnit() = looperUnitFactory.create()
    override fun createWarpsUnit() = warpsUnitFactory.create()
    override fun createClockUnit() = clockUnitFactory.create()
    override fun createFluxUnit() = fluxUnitFactory.create()
}
