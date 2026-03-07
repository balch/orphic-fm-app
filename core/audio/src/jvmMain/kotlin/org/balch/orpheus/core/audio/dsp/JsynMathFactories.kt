package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspMultiplyFactory::class])
class JsynMultiplyFactory @Inject constructor() : Multiply.Factory {
    override fun create(): Multiply = JsynMultiplyWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspAddFactory::class])
class JsynAddFactory @Inject constructor() : Add.Factory {
    override fun create(): Add = JsynAddWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspMultiplyAddFactory::class])
class JsynMultiplyAddFactory @Inject constructor() : MultiplyAdd.Factory {
    override fun create(): MultiplyAdd = JsynMultiplyAddWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspPassThroughFactory::class])
class JsynPassThroughFactory @Inject constructor() : PassThrough.Factory {
    override fun create(): PassThrough = JsynPassThroughWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspMinimumFactory::class])
class JsynMinimumFactory @Inject constructor() : Minimum.Factory {
    override fun create(): Minimum = JsynMinimumWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspMaximumFactory::class])
class JsynMaximumFactory @Inject constructor() : Maximum.Factory {
    override fun create(): Maximum = JsynMaximumWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspLinearRampFactory::class])
class JsynLinearRampFactory @Inject constructor() : LinearRamp.Factory {
    override fun create(): LinearRamp = JsynLinearRampWrapper()
}
