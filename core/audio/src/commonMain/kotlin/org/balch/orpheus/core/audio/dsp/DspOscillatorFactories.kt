package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspSineOscillatorFactory @Inject constructor() : SineOscillator.Factory {
    override fun create(): SineOscillator = DspSineOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspTriangleOscillatorFactory @Inject constructor() : TriangleOscillator.Factory {
    override fun create(): TriangleOscillator = DspTriangleOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspSquareOscillatorFactory @Inject constructor() : SquareOscillator.Factory {
    override fun create(): SquareOscillator = DspSquareOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspSawtoothOscillatorFactory @Inject constructor() : SawtoothOscillator.Factory {
    override fun create(): SawtoothOscillator = DspSawtoothOscillator()
}
