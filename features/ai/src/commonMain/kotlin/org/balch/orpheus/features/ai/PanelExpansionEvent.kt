package org.balch.orpheus.features.ai

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Available panels that can be expanded/collapsed.
 */
@LLMDescription("Panels in the app the can be expanded or collapsed.")
enum class PanelId {
    @LLMDescription("Panel allowing user to select a patch")
    PRESETS,
    @LLMDescription("Assign MIDI commands to control the synthesizer")
    MIDI,
    @LLMDescription("Display visualizations linked to the sound in the background")
    VIZ,
    @LLMDescription("Algorithmic Evolution Panel")
    EVO,
    @LLMDescription("Provide wave patterns to produce sounds")
    LFO,
    @LLMDescription("Add repeating lines to sounds")
    DELAY,
    @LLMDescription("Add spatial reverb effect")
    REVERB,
    @LLMDescription("Control volume characteristics of sounds")
    DISTORTION,
    @LLMDescription("Add texture to sounds")
    RESONATOR,
    @LLMDescription("Tidal Coding Panel for REPL")
    CODE,
    @LLMDescription("Panel allowing user to select a patch")
    AI,
    @LLMDescription("Drum Patterns Panel")
    BEATS,
    @LLMDescription("Drum Tuning Panel")
    DRUMS,
    @LLMDescription("Granular Molecule Synthesis")
    GRAINS,
    @LLMDescription("Record and replay audio")
    LOOPER,
    @LLMDescription("Cross Modulation")
    WARPS,
    @LLMDescription("Random music generator")
    FLUX,
    @LLMDescription("Assigns sounds to Flux outputs")
    FLUX_TRIGGERS,
    @LLMDescription("Speech synthesis panel showing AI speech output")
    SPEECH
}

/**
 * Event for requesting panel expansion or collapse.
 */
data class PanelExpansionEvent(
    val panelId: PanelId,
    val expand: Boolean
)

/**
 * Event bus for panel expansion/collapse requests.
 * 
 * This allows AI tools to request that specific panels be expanded or collapsed,
 * enabling seamless UI transitions during AI operations.
 */
@SingleIn(AppScope::class)
class PanelExpansionEventBus @Inject constructor() {
    
    private val log = logging("PanelExpansionEventBus")
    
    private val _events = MutableSharedFlow<PanelExpansionEvent>(
        replay = 1,  // Replay last event for late subscribers
        extraBufferCapacity = 5
    )
    
    /**
     * Flow of panel expansion events.
     */
    val events: SharedFlow<PanelExpansionEvent> = _events.asSharedFlow()
    
    /**
     * Request that a panel be expanded.
     */
    suspend fun expand(panelId: PanelId) {
        log.debug { "PanelExpansionEventBus: EXPAND ${panelId.name}" }
        _events.emit(PanelExpansionEvent(panelId, expand = true))
    }
    
    /**
     * Request that a panel be collapsed.
     */
    suspend fun collapse(panelId: PanelId) {
        log.debug { "PanelExpansionEventBus: COLLAPSE ${panelId.name}" }
        _events.emit(PanelExpansionEvent(panelId, expand = false))
    }
    
    /**
     * Request that multiple panels be expanded.
     */
    suspend fun expandAll(vararg panelIds: PanelId) {
        panelIds.forEach { expand(it) }
    }
}
