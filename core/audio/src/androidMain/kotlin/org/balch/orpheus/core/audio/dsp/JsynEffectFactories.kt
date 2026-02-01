package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynDelayLineFactory @Inject constructor() : DelayLine.Factory {
    override fun create(): DelayLine = JsynDelayLine()
}





@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynLooperUnitFactory @Inject constructor() : LooperUnit.Factory {
    override fun create(): LooperUnit = JsynLooperUnit()
}




