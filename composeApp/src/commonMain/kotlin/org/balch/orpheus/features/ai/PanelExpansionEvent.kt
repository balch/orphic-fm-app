package org.balch.orpheus.features.ai

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
enum class PanelId(val displayName: String) {
    PRESETS("Presets"),
    MIDI("MIDI"),
    STEREO("Stereo"),
    VIZ("Visualization"),
    EVO("Evolution"),
    LFO("Hyper LFO"),
    DELAY("Mod Delay"),
    DISTORTION("Distortion"),
    CODE("Live Code"),
    AI("AI Options")
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
        log.debug { "PanelExpansionEventBus: EXPAND ${panelId.displayName}" }
        _events.emit(PanelExpansionEvent(panelId, expand = true))
    }
    
    /**
     * Request that a panel be collapsed.
     */
    suspend fun collapse(panelId: PanelId) {
        log.debug { "PanelExpansionEventBus: COLLAPSE ${panelId.displayName}" }
        _events.emit(PanelExpansionEvent(panelId, expand = false))
    }
    
    /**
     * Request that multiple panels be expanded.
     */
    suspend fun expandAll(vararg panelIds: PanelId) {
        panelIds.forEach { expand(it) }
    }
}
