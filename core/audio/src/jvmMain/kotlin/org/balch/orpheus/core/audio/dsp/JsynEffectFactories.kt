package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspDelayLineFactory::class])
@Inject
class JsynDelayLineFactory() : DelayLine.Factory {
    override fun create(): DelayLine = JsynDelayLine()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspLooperUnitFactory::class])
@Inject
class JsynLooperUnitFactory() : LooperUnit.Factory {
    override fun create(): LooperUnit = JsynLooperUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspTtsPlayerUnitFactory::class])
@Inject
class JsynTtsPlayerUnitFactory() : TtsPlayerUnit.Factory {
    override fun create(): TtsPlayerUnit = JsynTtsPlayerUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspSpeechEffectsUnitFactory::class])
@Inject
class JsynSpeechEffectsUnitFactory() : SpeechEffectsUnit.Factory {
    override fun create(): SpeechEffectsUnit = JsynSpeechEffectsUnit()
}




