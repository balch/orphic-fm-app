package org.balch.orpheus.features.record

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.PresetsRepository
import org.balch.orpheus.core.presets.SynthPreset

/** Captures the live synth state and saves it next to the user's presets. */
@Inject
@ContributesBinding(AppScope::class)
class DefaultPresetSnapshotter(
    private val presetLoader: PresetLoader,
    private val presetsRepository: PresetsRepository,
) : PresetSnapshotter {
    override fun capture(name: String): SynthPreset = presetLoader.currentStateAsPreset(name)

    override suspend fun save(preset: SynthPreset) = presetsRepository.save(preset)
}
