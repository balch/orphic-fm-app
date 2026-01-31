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
class JsynFluxUnitFactory @Inject constructor() : FluxUnit.Factory {
    override fun create(): FluxUnit = JsynFluxUnit()
}
