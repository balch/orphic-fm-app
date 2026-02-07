package org.balch.orpheus.plugins.drum.engine

import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngineId

/**
 * Factory for drum-based [PlaitsEngine] implementations.
 * Creates independent instances of wrapped 808/FM drum engines.
 */
class DrumEngineFactory : PlaitsEngineFactory {
    override fun create(id: PlaitsEngineId): PlaitsEngine {
        val engine = when (id) {
            PlaitsEngineId.ANALOG_BASS_DRUM -> AnalogBassDrumEngine()
            PlaitsEngineId.ANALOG_SNARE_DRUM -> AnalogSnareDrumEngine()
            PlaitsEngineId.METALLIC_HI_HAT -> MetallicHiHatEngine()
            PlaitsEngineId.FM_DRUM -> FmDrumEngine()
            else -> error("DrumEngineFactory does not support $id")
        }
        engine.init()
        return engine
    }
}
