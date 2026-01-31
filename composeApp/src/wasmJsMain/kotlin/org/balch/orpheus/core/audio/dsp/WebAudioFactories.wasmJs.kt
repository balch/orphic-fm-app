package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubSineFactory @Inject constructor() : SineOscillator.Factory { override fun create() = StubSineOscillator() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubTriangleFactory @Inject constructor() : TriangleOscillator.Factory { override fun create() = StubTriangleOscillator() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubSquareFactory @Inject constructor() : SquareOscillator.Factory { override fun create() = StubSquareOscillator() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubSawtoothFactory @Inject constructor() : SawtoothOscillator.Factory { override fun create() = StubSawtoothOscillator() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubEnvelopeFactory @Inject constructor() : Envelope.Factory { override fun create() = StubEnvelope() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubDelayLineFactory @Inject constructor() : DelayLine.Factory { override fun create() = StubDelayLine() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubPeakFollowerFactory @Inject constructor() : PeakFollower.Factory { override fun create() = StubPeakFollower() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubLimiterFactory @Inject constructor() : Limiter.Factory { override fun create() = StubLimiter() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubMultiplyFactory @Inject constructor() : Multiply.Factory { override fun create() = StubMultiply() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubAddFactory @Inject constructor() : Add.Factory { override fun create() = StubAdd() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubMultiplyAddFactory @Inject constructor() : MultiplyAdd.Factory { override fun create() = StubMultiplyAdd() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubPassThroughFactory @Inject constructor() : PassThrough.Factory { override fun create() = StubPassThrough() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubMinimumFactory @Inject constructor() : Minimum.Factory { override fun create() = StubMinimum() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubMaximumFactory @Inject constructor() : Maximum.Factory { override fun create() = StubMaximum() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubLinearRampFactory @Inject constructor() : LinearRamp.Factory { override fun create() = StubLinearRamp() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubAutomationPlayerFactory @Inject constructor() : AutomationPlayer.Factory { override fun create() = StubAutomationPlayer() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubDrumUnitFactory @Inject constructor() : DrumUnit.Factory { override fun create() = StubDrumUnit() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubResonatorUnitFactory @Inject constructor() : ResonatorUnit.Factory { override fun create() = StubResonatorUnit() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubGrainsUnitFactory @Inject constructor() : GrainsUnit.Factory { override fun create() = StubGrainsUnit() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubLooperUnitFactory @Inject constructor() : LooperUnit.Factory { override fun create() = StubLooperUnit() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubWarpsUnitFactory @Inject constructor() : WarpsUnit.Factory { override fun create() = StubWarpsUnit() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubClockUnitFactory @Inject constructor() : ClockUnit.Factory { override fun create() = StubClockUnit() }

@SingleIn(AppScope::class) @ContributesBinding(AppScope::class)
class StubFluxUnitFactory @Inject constructor() : FluxUnit.Factory { override fun create() = StubFluxUnit() }
