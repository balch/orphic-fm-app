package org.balch.orpheus.core.presets

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BEATS_URI
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DRUM_URI
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
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
    private val synthController: SynthController,
) {

    // Shared flow to broadcast preset changes to ViewModels
    private val _presetFlow =
        MutableSharedFlow<SynthPreset>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val presetFlow: SharedFlow<SynthPreset> = _presetFlow.asSharedFlow()

    // Non-plugin feature state (e.g. text input, voice selection)
    private val _featureState = mutableMapOf<String, PortValue>()

    fun setFeatureValue(key: String, value: PortValue) {
        _featureState[key] = value
    }

    /**
     * Apply a preset by restoring all port values via PortRegistry.
     * After restoring, holds and beats are forced off so the user starts
     * from a silent state and can bring things up intentionally.
     */
    fun applyPreset(preset: SynthPreset) {
        // Update global tempo immediately
        globalTempo.setBpm(preset.bpm.toDouble())

        // Restore all port values via registry (resets to defaults first, then applies overrides)
        portRegistry.restoreState(preset.portValues)

        // Force all holds off — user brings them up intentionally
        for (i in 0..2) {
            portRegistry.setPortValue(
                "$VOICE_URI:${VoiceSymbol.quadHold(i).symbol}",
                PortValue.FloatValue(0f)
            )
        }

        // Force drums/beats bypassed
        portRegistry.setPortValue(
            "$DRUM_URI:${DrumSymbol.BYPASS.symbol}",
            PortValue.BoolValue(true)
        )

        // Sync StateFlows with engine state set by restoreState
        synthController.refreshControlFlows()

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
            portValues = portRegistry.captureState() + _featureState
        )
    }
}
