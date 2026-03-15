package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.engine.NativeOnlyEngine

/**
 * Factory for all [PlaitsEngine] implementations.
 * All engines are now C++ native-only — returns [NativeOnlyEngine] for every engine ID.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<PlaitsEngineFactory>())
class PlaitsEngineFactoryImpl : PlaitsEngineFactory {

    override fun create(id: PlaitsEngineId): PlaitsEngine = NativeOnlyEngine(id)
}
