package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspMultiplyFactory::class])
@Inject
class JsynMultiplyFactory() : Multiply.Factory {
    override fun create(): Multiply = JsynMultiplyWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspAddFactory::class])
@Inject
class JsynAddFactory() : Add.Factory {
    override fun create(): Add = JsynAddWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspMultiplyAddFactory::class])
@Inject
class JsynMultiplyAddFactory() : MultiplyAdd.Factory {
    override fun create(): MultiplyAdd = JsynMultiplyAddWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspPassThroughFactory::class])
@Inject
class JsynPassThroughFactory() : PassThrough.Factory {
    override fun create(): PassThrough = JsynPassThroughWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspMinimumFactory::class])
@Inject
class JsynMinimumFactory() : Minimum.Factory {
    override fun create(): Minimum = JsynMinimumWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspMaximumFactory::class])
@Inject
class JsynMaximumFactory() : Maximum.Factory {
    override fun create(): Maximum = JsynMaximumWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspLinearRampFactory::class])
@Inject
class JsynLinearRampFactory() : LinearRamp.Factory {
    override fun create(): LinearRamp = JsynLinearRampWrapper()
}
