package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynSineOscillatorFactory @Inject constructor() : SineOscillator.Factory {
    override fun create(): SineOscillator = JsynSineOscillatorWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynTriangleOscillatorFactory @Inject constructor() : TriangleOscillator.Factory {
    override fun create(): TriangleOscillator = JsynTriangleOscillatorWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynSquareOscillatorFactory @Inject constructor() : SquareOscillator.Factory {
    override fun create(): SquareOscillator = JsynSquareOscillatorWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynSawtoothOscillatorFactory @Inject constructor() : SawtoothOscillator.Factory {
    override fun create(): SawtoothOscillator = JsynSawtoothOscillatorWrapper()
}
