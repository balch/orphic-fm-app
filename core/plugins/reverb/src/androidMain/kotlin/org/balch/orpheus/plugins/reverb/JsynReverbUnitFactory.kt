package org.balch.orpheus.plugins.reverb

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.ReverbUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynReverbUnitFactory @Inject constructor() : ReverbUnit.Factory {
    override fun create(): ReverbUnit = JsynReverbUnit()
}
