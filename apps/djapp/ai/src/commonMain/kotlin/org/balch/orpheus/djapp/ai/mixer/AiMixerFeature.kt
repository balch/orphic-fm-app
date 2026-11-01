package org.balch.orpheus.djapp.ai.mixer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.models.SlotLock

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
    val togglePin: (Int) -> Unit,
    val dismissCapture: () -> Unit = {},
    val toggleChange: (Int) -> Unit = {},
    val markPoint: () -> Unit = {},
    val hear: (Int) -> Unit = {},
    val endHear: () -> Unit = {},
    val rollBack: (Int) -> Unit = {},
    /** Turns a voice knob on the track's locked slot and pins it; a null value returns knob and pin to the file. Off under AUTO. */
    val setVoiceKnob: (Int, EngineKnob, Float?) -> Unit = { _, _, _ -> },
    /** Holds a track on its EDM or SPACE engine, or back to AUTO; a vibe row that plays on. */
    val setSlotLock: (Int, SlotLock) -> Unit = { _, _ -> },
    /**
     * Swaps a slot's engine from the strip's picker; the song plays on and the swap lands on the track's next note.
     * A DX patch pick passes the patch's [harmonics] so engine and patch change as one edit; null leaves them alone.
     */
    val setEngine: (track: Int, slot: EngineSlot, engine: OrpheusEngineId, harmonics: Float?) -> Unit = { _, _, _, _ -> },
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
            togglePin = {},
            dismissCapture = {},
        )
    }
}

/** Feature contract for the AI edition's mixer panel. */
interface AiMixerFeature : SynthFeature<AiMixerUiState, AiMixerActions> {
    /** Cold: polls the controller's meters at 20 Hz while collected, so metering idles off when nobody collects. */
    val meters: Flow<MeterSnapshot>

    /** Each control the agent or MCP just changed, for the panel to flash. */
    val agentEdits: Flow<MixControl> get() = emptyFlow()

    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}
