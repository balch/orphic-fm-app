package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioSineOscillatorFactory @Inject constructor(private val engine: OrpheusAudioEngine) : SineOscillator.Factory {
    override fun create(): SineOscillator = WebAudioSineOscillator(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioTriangleOscillatorFactory @Inject constructor(private val engine: OrpheusAudioEngine) : TriangleOscillator.Factory {
    override fun create(): TriangleOscillator = WebAudioTriangleOscillator(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioSquareOscillatorFactory @Inject constructor(private val engine: OrpheusAudioEngine) : SquareOscillator.Factory {
    override fun create(): SquareOscillator = WebAudioSquareOscillator(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioSawtoothOscillatorFactory @Inject constructor(private val engine: OrpheusAudioEngine) : SawtoothOscillator.Factory {
    override fun create(): SawtoothOscillator = WebAudioSawtoothOscillator(engine.webAudioContext)
}
