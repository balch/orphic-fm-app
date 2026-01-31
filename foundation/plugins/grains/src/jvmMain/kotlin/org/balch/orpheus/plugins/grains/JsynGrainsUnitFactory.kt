package org.balch.orpheus.plugins.grains

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.GrainsUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynGrainsUnitFactory @Inject constructor() : GrainsUnit.Factory {
    override fun create(): GrainsUnit = JsynGrainsUnit()
}
