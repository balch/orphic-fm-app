package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspMultiplyFactory() : Multiply.Factory {
    override fun create(): Multiply = DspMultiply()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspAddFactory() : Add.Factory {
    override fun create(): Add = DspAdd()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspMultiplyAddFactory() : MultiplyAdd.Factory {
    override fun create(): MultiplyAdd = DspMultiplyAdd()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspPassThroughFactory() : PassThrough.Factory {
    override fun create(): PassThrough = DspPassThrough()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspMinimumFactory() : Minimum.Factory {
    override fun create(): Minimum = DspMinimum()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspMaximumFactory() : Maximum.Factory {
    override fun create(): Maximum = DspMaximum()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspLinearRampFactory() : LinearRamp.Factory {
    override fun create(): LinearRamp = DspLinearRamp()
}
