package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspDelayLineFactory @Inject constructor() : DelayLine.Factory {
    override fun create(): DelayLine = DspDelayLine()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspLooperUnitFactory @Inject constructor() : LooperUnit.Factory {
    override fun create(): LooperUnit = DspLooperUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspTtsPlayerUnitFactory @Inject constructor() : TtsPlayerUnit.Factory {
    override fun create(): TtsPlayerUnit = DspTtsPlayerUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspSpeechEffectsUnitFactory @Inject constructor() : SpeechEffectsUnit.Factory {
    override fun create(): SpeechEffectsUnit = DspSpeechEffectsUnit()
}
