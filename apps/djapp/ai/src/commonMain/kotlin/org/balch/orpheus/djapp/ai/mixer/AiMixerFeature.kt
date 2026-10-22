package org.balch.orpheus.djapp.ai.mixer

import kotlinx.coroutines.flow.Flow
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.Scope

/** What the mixer panel can do. Track-indexed edits are whole-vibe or section per [AiMixerUiState.scope]. */
data class AiMixerActions(
    val setLevelDb: (Int, Float) -> Unit,
    val setPan: (Int, Float) -> Unit,
    val setReverb: (Int, Float) -> Unit,
    val setDelay: (Int, Float) -> Unit,
    val toggleMute: (Int) -> Unit,
    val toggleSolo: (Int) -> Unit,
    val setMacro: (Macro, Float) -> Unit,
    val setScope: (Scope) -> Unit,
    val jumpTo: (Int) -> Unit,
    val setLoop: (Boolean) -> Unit,
    val reset: () -> Unit,
    val capture: () -> Unit,
    val dismissNotice: () -> Unit,
) {
    companion object {
        val EMPTY = AiMixerActions(
            setLevelDb = { _, _ -> },
            setPan = { _, _ -> },
            setReverb = { _, _ -> },
            setDelay = { _, _ -> },
            toggleMute = {},
            toggleSolo = {},
            setMacro = { _, _ -> },
            setScope = {},
            jumpTo = {},
            setLoop = {},
            reset = {},
            capture = {},
            dismissNotice = {},
        )
    }
}

/** Feature contract for the AI edition's mixer panel. */
interface AiMixerFeature : SynthFeature<AiMixerUiState, AiMixerActions> {
    /** Cold: polls the controller's meters at 20 Hz while collected, so metering idles off when nobody collects. */
    val meters: Flow<MeterSnapshot>

    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}
