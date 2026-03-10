package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspSineOscillatorFactory() : SineOscillator.Factory {
    override fun create(): SineOscillator = DspSineOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspTriangleOscillatorFactory() : TriangleOscillator.Factory {
    override fun create(): TriangleOscillator = DspTriangleOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspSquareOscillatorFactory() : SquareOscillator.Factory {
    override fun create(): SquareOscillator = DspSquareOscillator()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspSawtoothOscillatorFactory() : SawtoothOscillator.Factory {
    override fun create(): SawtoothOscillator = DspSawtoothOscillator()
}
