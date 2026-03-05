package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeMultiplyFactory @Inject constructor() : Multiply.Factory {
    override fun create(): Multiply = OboeMultiply()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeAddFactory @Inject constructor() : Add.Factory {
    override fun create(): Add = OboeAdd()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeMultiplyAddFactory @Inject constructor() : MultiplyAdd.Factory {
    override fun create(): MultiplyAdd = OboeMultiplyAdd()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboePassThroughFactory @Inject constructor() : PassThrough.Factory {
    override fun create(): PassThrough = OboePassThroughUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeMinimumFactory @Inject constructor() : Minimum.Factory {
    override fun create(): Minimum = OboeMinimum()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeMaximumFactory @Inject constructor() : Maximum.Factory {
    override fun create(): Maximum = OboeMaximum()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeLinearRampFactory @Inject constructor() : LinearRamp.Factory {
    override fun create(): LinearRamp = OboeLinearRamp()
}
