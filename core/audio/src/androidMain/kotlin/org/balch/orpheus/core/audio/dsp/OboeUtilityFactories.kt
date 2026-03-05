package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeAutomationPlayerFactory @Inject constructor() : AutomationPlayer.Factory {
    override fun create(): AutomationPlayer = OboeAutomationPlayer()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeClockUnitFactory @Inject constructor() : ClockUnit.Factory {
    override fun create(): ClockUnit = OboeClockUnit()
}
