package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioDelayLineFactory @Inject constructor(private val engine: OrpheusAudioEngine) : DelayLine.Factory {
    override fun create(): DelayLine = WebAudioDelayLine(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioResonatorUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : ResonatorUnit.Factory {
    override fun create(): ResonatorUnit = WebAudioResonatorUnit(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioGrainsUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : GrainsUnit.Factory {
    override fun create(): GrainsUnit = WebAudioGrainsUnit(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioLooperUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : LooperUnit.Factory {
    override fun create(): LooperUnit = WebAudioLooperUnit(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioWarpsUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : WarpsUnit.Factory {
    override fun create(): WarpsUnit = WebAudioWarpsUnit(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioFluxUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : FluxUnit.Factory {
    override fun create(): FluxUnit = WebAudioFluxUnit(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioReverbUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : ReverbUnit.Factory {
    override fun create(): ReverbUnit = WebAudioReverbUnit(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioTtsPlayerUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : TtsPlayerUnit.Factory {
    override fun create(): TtsPlayerUnit = WebAudioTtsPlayerUnit(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioSpeechEffectsUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : SpeechEffectsUnit.Factory {
    override fun create(): SpeechEffectsUnit = WebAudioSpeechEffectsUnit(engine.webAudioContext)
}
