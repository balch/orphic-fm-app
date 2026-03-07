package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.plugins.drum.engine.DrumEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.engine.AdditiveEngine
import org.balch.orpheus.plugins.plaits.engine.ChordEngine
import org.balch.orpheus.plugins.plaits.engine.FmEngine
import org.balch.orpheus.plugins.plaits.engine.GrainEngine
import org.balch.orpheus.plugins.plaits.engine.ModalEngine
import org.balch.orpheus.plugins.plaits.engine.NoiseEngine
import org.balch.orpheus.plugins.plaits.engine.ParticleEngine
import org.balch.orpheus.plugins.plaits.engine.SpeechEngine
import org.balch.orpheus.plugins.plaits.engine.StringEngine
import org.balch.orpheus.plugins.plaits.engine.SwarmEngine
import org.balch.orpheus.plugins.plaits.engine.VirtualAnalogEngine
import org.balch.orpheus.plugins.plaits.engine.WaveshapingEngine
import org.balch.orpheus.plugins.plaits.engine.WavetableEngine

/**
 * Unified factory for all [PlaitsEngine] implementations.
 * Delegates drum engines to [DrumEngineFactory] and creates pitched engines directly.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<PlaitsEngineFactory>())
class PlaitsEngineFactoryImpl(
    private val drumFactory: DrumEngineFactory,
) : PlaitsEngineFactory {

    override fun create(id: PlaitsEngineId): PlaitsEngine {
        return when (id) {
            PlaitsEngineId.ANALOG_BASS_DRUM,
            PlaitsEngineId.ANALOG_SNARE_DRUM,
            PlaitsEngineId.METALLIC_HI_HAT,
            PlaitsEngineId.FM_DRUM -> drumFactory.create(id)

            PlaitsEngineId.FM -> FmEngine().also { it.init() }
            PlaitsEngineId.NOISE -> NoiseEngine().also { it.init() }
            PlaitsEngineId.WAVESHAPING -> WaveshapingEngine().also { it.init() }
            PlaitsEngineId.VIRTUAL_ANALOG -> VirtualAnalogEngine().also { it.init() }
            PlaitsEngineId.ADDITIVE -> AdditiveEngine().also { it.init() }
            PlaitsEngineId.GRAIN -> GrainEngine().also { it.init() }
            PlaitsEngineId.STRING -> StringEngine().also { it.init() }
            PlaitsEngineId.MODAL -> ModalEngine().also { it.init() }
            PlaitsEngineId.PARTICLE -> ParticleEngine().also { it.init() }
            PlaitsEngineId.SWARM -> SwarmEngine().also { it.init() }
            PlaitsEngineId.CHORD -> ChordEngine().also { it.init() }
            PlaitsEngineId.WAVETABLE -> WavetableEngine().also { it.init() }
            PlaitsEngineId.SPEECH -> SpeechEngine().also { it.init() }
        }
    }
}
