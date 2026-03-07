package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspMultiplyFactory @Inject constructor() : Multiply.Factory {
    override fun create(): Multiply = DspMultiply()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspAddFactory @Inject constructor() : Add.Factory {
    override fun create(): Add = DspAdd()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspMultiplyAddFactory @Inject constructor() : MultiplyAdd.Factory {
    override fun create(): MultiplyAdd = DspMultiplyAdd()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspPassThroughFactory @Inject constructor() : PassThrough.Factory {
    override fun create(): PassThrough = DspPassThrough()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspMinimumFactory @Inject constructor() : Minimum.Factory {
    override fun create(): Minimum = DspMinimum()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspMaximumFactory @Inject constructor() : Maximum.Factory {
    override fun create(): Maximum = DspMaximum()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspLinearRampFactory @Inject constructor() : LinearRamp.Factory {
    override fun create(): LinearRamp = DspLinearRamp()
}
