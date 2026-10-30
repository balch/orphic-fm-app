package org.balch.orpheus.features.voice

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.DuoPatchBay
import org.balch.orpheus.core.audio.patch.duoModSourceLegacyKey
import org.balch.orpheus.core.audio.patch.duoPatchKey
import org.balch.orpheus.core.audio.patch.patchFromPreset
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.di.StartupRoot
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.SynthPreset

/**
 * Restores duo patches from loaded presets and mirrors bay edits into the preset feature state.
 * A startup root, so presets restore patches even when the voice panel never opens.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<@StartupRoot Any>())
class DuoPatchPresetSync(
    private val presetLoader: PresetLoader,
    private val bay: DuoPatchBay,
    scope: AppCoroutineScope,
) {
    init {
        start(scope)
    }

    private fun start(scope: CoroutineScope) {
        // applyPreset emits after restoreState, so these land after its engine writes and parking.
        scope.launch {
            presetLoader.presetFlow.collect(::restore)
        }
        scope.launch {
            var written: List<DuoPatch>? = null
            bay.patches.collect { patches ->
                patches.forEachIndexed { duo, patch ->
                    if (written?.getOrNull(duo) != patch) {
                        presetLoader.setFeatureValue(duoPatchKey(duo), PortValue.StringValue(patch.encode()))
                    }
                }
                written = patches
            }
        }
    }

    private fun restore(preset: SynthPreset) {
        for (duo in 0 until 6) {
            val values = preset.portValues
            val engineId = values[VoiceSymbol.duoEngine(duo).controlId.key]?.asInt() ?: OrpheusEngineId.OSC.id
            val patch = patchFromPreset(
                encoded = (values[duoPatchKey(duo)] as? PortValue.StringValue)?.value,
                legacySource = values[duoModSourceLegacyKey(duo)]?.asFloat(),
                engine = OrpheusEngineId.fromId(engineId),
                duoIndex = duo,
            )
            bay.setPatch(duo, patch)
        }
    }
}
