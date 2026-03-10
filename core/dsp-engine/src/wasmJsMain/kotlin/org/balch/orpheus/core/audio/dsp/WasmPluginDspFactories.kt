package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * WASM-specific factory bindings for complex plugin DSP units.
 *
 * These factories create real Dsp*Unit implementations from commonMain
 * (e.g., DspResonatorUnit, DspPlaitsUnit) that run full DSP processing
 * via the DspGraphScheduler.
 *
 * Basic unit factories (oscillators, math, dynamics, effects, utilities)
 * are provided by commonMain via @ContributesBinding and do NOT need
 * WASM-specific duplicates.
 */

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WasmResonatorUnitFactory() : ResonatorUnit.Factory {
    override fun create(): ResonatorUnit = DspResonatorUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WasmGrainsUnitFactory() : GrainsUnit.Factory {
    override fun create(): GrainsUnit = DspGrainsUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WasmWarpsUnitFactory() : WarpsUnit.Factory {
    override fun create(): WarpsUnit = DspWarpsUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WasmFluxUnitFactory() : FluxUnit.Factory {
    override fun create(): FluxUnit = DspFluxUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WasmReverbUnitFactory() : ReverbUnit.Factory {
    override fun create(): ReverbUnit = DspReverbUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WasmPlaitsUnitFactory() : PlaitsUnit.Factory {
    override fun create(): PlaitsUnit = DspPlaitsUnit()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WasmDrumUnitFactory() : DrumUnit.Factory {
    override fun create(): DrumUnit = DspDrumUnit()
}
