package org.balch.orpheus.plugins.warps

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.WarpsUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynWarpsUnitFactory @Inject constructor() : WarpsUnit.Factory {
    override fun create(): WarpsUnit = JsynWarpsUnit()
}
