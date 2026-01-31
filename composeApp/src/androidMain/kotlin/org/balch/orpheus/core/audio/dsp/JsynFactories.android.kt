package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynSineOscillatorFactory @Inject constructor() : SineOscillator.Factory {
    override fun create(): SineOscillator = JsynSineOscillatorWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynTriangleOscillatorFactory @Inject constructor() : TriangleOscillator.Factory {
    override fun create(): TriangleOscillator = JsynTriangleOscillatorWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynSquareOscillatorFactory @Inject constructor() : SquareOscillator.Factory {
    override fun create(): SquareOscillator = JsynSquareOscillatorWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynSawtoothOscillatorFactory @Inject constructor() : SawtoothOscillator.Factory {
    override fun create(): SawtoothOscillator = JsynSawtoothOscillatorWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynEnvelopeFactory @Inject constructor() : Envelope.Factory {
    override fun create(): Envelope = JsynEnvelope()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynDelayLineFactory @Inject constructor() : DelayLine.Factory {
    override fun create(): DelayLine = JsynDelayLine()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynPeakFollowerFactory @Inject constructor() : PeakFollower.Factory {
    override fun create(): PeakFollower = JsynPeakFollowerWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynLimiterFactory @Inject constructor() : Limiter.Factory {
    override fun create(): Limiter = JsynLimiter()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynMultiplyFactory @Inject constructor() : Multiply.Factory {
    override fun create(): Multiply = JsynMultiplyWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynAddFactory @Inject constructor() : Add.Factory {
    override fun create(): Add = JsynAddWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynMultiplyAddFactory @Inject constructor() : MultiplyAdd.Factory {
    override fun create(): MultiplyAdd = JsynMultiplyAddWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynPassThroughFactory @Inject constructor() : PassThrough.Factory {
    override fun create(): PassThrough = JsynPassThroughWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynMinimumFactory @Inject constructor() : Minimum.Factory {
    override fun create(): Minimum = JsynMinimumWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynMaximumFactory @Inject constructor() : Maximum.Factory {
    override fun create(): Maximum = JsynMaximumWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynLinearRampFactory @Inject constructor() : LinearRamp.Factory {
    override fun create(): LinearRamp = JsynLinearRampWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynAutomationPlayerFactory @Inject constructor() : AutomationPlayer.Factory {
    override fun create(): AutomationPlayer = JsynAutomationPlayer()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynDrumUnitFactory @Inject constructor() : DrumUnit.Factory {
    override fun create(): DrumUnit = JsynDrumUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynResonatorUnitFactory @Inject constructor() : ResonatorUnit.Factory {
    override fun create(): ResonatorUnit = JsynResonatorUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynGrainsUnitFactory @Inject constructor() : GrainsUnit.Factory {
    override fun create(): GrainsUnit = JsynGrainsUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynLooperUnitFactory @Inject constructor() : LooperUnit.Factory {
    override fun create(): LooperUnit = JsynLooperUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynWarpsUnitFactory @Inject constructor() : WarpsUnit.Factory {
    override fun create(): WarpsUnit = JsynWarpsUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynClockUnitFactory @Inject constructor() : ClockUnit.Factory {
    override fun create(): ClockUnit = JsynClockUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynFluxUnitFactory @Inject constructor() : FluxUnit.Factory {
    override fun create(): FluxUnit = JsynFluxUnit()
}
