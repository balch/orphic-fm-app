package org.balch.orpheus.plugins.flux

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.FluxUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynFluxUnitFactory @Inject constructor() : FluxUnit.Factory {
    override fun create(): FluxUnit = JsynFluxUnit()
}
