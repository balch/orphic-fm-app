package org.balch.orpheus.plugins.drum

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.DrumUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynDrumUnitFactory @Inject constructor() : DrumUnit.Factory {
    override fun create(): DrumUnit = JsynDrumUnit()
}
