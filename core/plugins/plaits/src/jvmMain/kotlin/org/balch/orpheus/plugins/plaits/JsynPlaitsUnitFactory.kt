package org.balch.orpheus.plugins.plaits

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.PlaitsUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynPlaitsUnitFactory @Inject constructor() : PlaitsUnit.Factory {
    override fun create(): PlaitsUnit = JsynPlaitsUnit()
}
