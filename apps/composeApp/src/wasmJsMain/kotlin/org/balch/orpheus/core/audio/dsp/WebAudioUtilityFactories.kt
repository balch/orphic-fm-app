package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioAutomationPlayerFactory @Inject constructor(private val engine: OrpheusAudioEngine) : AutomationPlayer.Factory {
    override fun create(): AutomationPlayer = WebAudioAutomationPlayer(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioDrumUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : DrumUnit.Factory {
    override fun create(): DrumUnit = WebAudioDrumUnit(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioClockUnitFactory @Inject constructor(private val engine: OrpheusAudioEngine) : ClockUnit.Factory {
    override fun create(): ClockUnit = WebAudioClockUnit(engine.webAudioContext)
}
