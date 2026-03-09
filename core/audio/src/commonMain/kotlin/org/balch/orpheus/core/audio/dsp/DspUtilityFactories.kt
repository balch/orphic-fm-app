package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspAutomationPlayerFactory @Inject constructor() : AutomationPlayer.Factory {
    override fun create(): AutomationPlayer = DspAutomationPlayer()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspClockUnitFactory @Inject constructor() : ClockUnit.Factory {
    override fun create(): ClockUnit = DspClockUnit()
}
