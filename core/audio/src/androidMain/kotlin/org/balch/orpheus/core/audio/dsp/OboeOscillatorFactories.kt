package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeSineOscillatorFactory @Inject constructor() : SineOscillator.Factory {
    override fun create(): SineOscillator = OboeSineOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeTriangleOscillatorFactory @Inject constructor() : TriangleOscillator.Factory {
    override fun create(): TriangleOscillator = OboeTriangleOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeSquareOscillatorFactory @Inject constructor() : SquareOscillator.Factory {
    override fun create(): SquareOscillator = OboeSquareOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeSawtoothOscillatorFactory @Inject constructor() : SawtoothOscillator.Factory {
    override fun create(): SawtoothOscillator = OboeSawtoothOscillator()
}
