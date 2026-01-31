package org.balch.orpheus.plugins.resonator

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.ResonatorUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynResonatorUnitFactory @Inject constructor() : ResonatorUnit.Factory {
    override fun create(): ResonatorUnit = JsynResonatorUnit()
}
