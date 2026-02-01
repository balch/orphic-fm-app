package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioMultiplyFactory @Inject constructor(private val engine: AudioEngine) : Multiply.Factory {
    override fun create(): Multiply = WebAudioMultiply((engine as OrpheusAudioEngine).webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioAddFactory @Inject constructor(private val engine: OrpheusAudioEngine) : Add.Factory {
    override fun create(): Add = WebAudioAdd(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioMultiplyAddFactory @Inject constructor(private val engine: OrpheusAudioEngine) : MultiplyAdd.Factory {
    override fun create(): MultiplyAdd = WebAudioMultiplyAdd(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioPassThroughFactory @Inject constructor(private val engine: OrpheusAudioEngine) : PassThrough.Factory {
    override fun create(): PassThrough = WebAudioPassThrough(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioMinimumFactory @Inject constructor(private val engine: OrpheusAudioEngine) : Minimum.Factory {
    override fun create(): Minimum = WebAudioMinimum(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioMaximumFactory @Inject constructor(private val engine: OrpheusAudioEngine) : Maximum.Factory {
    override fun create(): Maximum = WebAudioMaximum(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioLinearRampFactory @Inject constructor(private val engine: OrpheusAudioEngine) : LinearRamp.Factory {
    override fun create(): LinearRamp = WebAudioLinearRamp(engine.webAudioContext)
}
