package org.balch.orpheus.core.presets

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.core.tempo.GlobalTempo

/**
 * Handles preset loading using PortRegistry for generic port access.
 * This is a simpler version that delegates all port handling to plugins via the DSL.
 */
@SingleIn(AppScope::class)
@Inject
class PresetLoader(
    private val portRegistry: PortRegistry,
    private val globalTempo: GlobalTempo,
) {

    // Shared flow to broadcast preset changes to ViewModels
    private val _presetFlow =
        MutableSharedFlow<SynthPreset>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val presetFlow: SharedFlow<SynthPreset> = _presetFlow.asSharedFlow()

    /**
     * Apply a preset by restoring all port values via PortRegistry.
     */
    fun applyPreset(preset: SynthPreset) {
        // Update global tempo immediately
        globalTempo.setBpm(preset.bpm.toDouble())
        
        // Restore all port values via registry
        portRegistry.restoreState(preset.portValues)
        
        // Broadcast to ViewModels for UI updates
        _presetFlow.tryEmit(preset)
    }

    /**
     * Capture current state from all plugins via PortRegistry.
     */
    fun currentStateAsPreset(name: String): SynthPreset {
        return SynthPreset(
            name = name,
            bpm = globalTempo.getBpm().toFloat(),
            portValues = portRegistry.captureState()
        )
    }
}
