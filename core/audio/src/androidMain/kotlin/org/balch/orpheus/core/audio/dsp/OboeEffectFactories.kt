package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeDelayLineFactory @Inject constructor() : DelayLine.Factory {
    override fun create(): DelayLine = OboeDelayLine()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeLooperUnitFactory @Inject constructor() : LooperUnit.Factory {
    override fun create(): LooperUnit = OboeLooperUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeTtsPlayerUnitFactory @Inject constructor() : TtsPlayerUnit.Factory {
    override fun create(): TtsPlayerUnit = OboeTtsPlayerUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeSpeechEffectsUnitFactory @Inject constructor() : SpeechEffectsUnit.Factory {
    override fun create(): SpeechEffectsUnit = OboeSpeechEffectsUnit()
}
