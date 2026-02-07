package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.plugins.drum.engine.DrumEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.engine.FmEngine
import org.balch.orpheus.plugins.plaits.engine.NoiseEngine
import org.balch.orpheus.plugins.plaits.engine.WaveshapingEngine

/**
 * Unified factory for all [PlaitsEngine] implementations.
 * Delegates drum engines to [DrumEngineFactory] and creates pitched engines directly.
 */
class PlaitsEngineFactoryImpl : PlaitsEngineFactory {
    private val drumFactory = DrumEngineFactory()

    override fun create(id: PlaitsEngineId): PlaitsEngine {
        return when (id) {
            PlaitsEngineId.ANALOG_BASS_DRUM,
            PlaitsEngineId.ANALOG_SNARE_DRUM,
            PlaitsEngineId.METALLIC_HI_HAT,
            PlaitsEngineId.FM_DRUM -> drumFactory.create(id)

            PlaitsEngineId.FM -> FmEngine().also { it.init() }
            PlaitsEngineId.NOISE -> NoiseEngine().also { it.init() }
            PlaitsEngineId.WAVESHAPING -> WaveshapingEngine().also { it.init() }
        }
    }
}
