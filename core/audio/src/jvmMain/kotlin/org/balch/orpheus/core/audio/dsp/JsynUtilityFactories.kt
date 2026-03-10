package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspAutomationPlayerFactory::class])
@Inject
class JsynAutomationPlayerFactory() : AutomationPlayer.Factory {
    override fun create(): AutomationPlayer = JsynAutomationPlayer()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspClockUnitFactory::class])
@Inject
class JsynClockUnitFactory() : ClockUnit.Factory {
    override fun create(): ClockUnit = JsynClockUnit()
}
