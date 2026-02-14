package org.balch.orpheus.features.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.panels.PanelSet

/**
 * Event for requesting panel expansion or collapse.
 */
data class PanelExpansionEvent(
    val panelId: PanelId,
    val expand: Boolean
)

/**
 * Event for applying a complete panel set configuration.
 */
data class PanelSetEvent(
    val panelSet: PanelSet
)

/**
 * Event bus for panel expansion/collapse requests and panel set changes.
 *
 * This allows AI tools to request that specific panels be expanded or collapsed,
 * or to apply an entire panel set configuration.
 */
@SingleIn(AppScope::class)
class PanelExpansionEventBus @Inject constructor() {

    private val log = logging("PanelExpansionEventBus")

    private val _events = MutableSharedFlow<PanelExpansionEvent>(
        replay = 1,  // Replay last event for late subscribers
        extraBufferCapacity = 5
    )

    private val _panelSetEvents = MutableSharedFlow<PanelSetEvent>(
        replay = 1,
        extraBufferCapacity = 5
    )

    /**
     * Flow of panel expansion events.
     */
    val events: SharedFlow<PanelExpansionEvent> = _events.asSharedFlow()

    /**
     * Flow of panel set events.
     */
    val panelSetEvents: SharedFlow<PanelSetEvent> = _panelSetEvents.asSharedFlow()

    /**
     * Request that a panel be expanded.
     */
    suspend fun expand(panelId: PanelId) {
        log.debug { "PanelExpansionEventBus: EXPAND ${panelId.id}" }
        _events.emit(PanelExpansionEvent(panelId, expand = true))
    }

    /**
     * Request that a panel be collapsed.
     */
    suspend fun collapse(panelId: PanelId) {
        log.debug { "PanelExpansionEventBus: COLLAPSE ${panelId.id}" }
        _events.emit(PanelExpansionEvent(panelId, expand = false))
    }

    /**
     * Request that multiple panels be expanded.
     */
    suspend fun expandAll(panelIds: List<PanelId>) {
        panelIds.forEach { expand(it) }
    }

    /**
     * Apply a complete panel set configuration.
     */
    suspend fun applyPanelSet(panelSet: PanelSet) {
        log.debug { "PanelExpansionEventBus: APPLY SET ${panelSet.name} (${panelSet.panels.size} panels)" }
        _panelSetEvents.emit(PanelSetEvent(panelSet))
    }
}
