package org.balch.orpheus.features.record

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.SynthEngine

@Inject
@ContributesBinding(AppScope::class)
class SynthEngineMasterLevel(engine: SynthEngine) : MasterLevelSource {
    override val level: StateFlow<Float> = engine.masterLevelFlow
}
