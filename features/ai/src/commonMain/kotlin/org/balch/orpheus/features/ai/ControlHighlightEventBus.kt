package org.balch.orpheus.features.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent state holder for AI-driven control highlights.
 *
 * Unlike [PanelExpansionEventBus] which uses SharedFlow for fire-and-forget events,
 * this uses StateFlow because highlight state is persistent until explicitly cleared.
 */
@SingleIn(AppScope::class)
class ControlHighlightEventBus @Inject constructor() {

    private val log = logging("ControlHighlightEventBus")

    private val _highlightedControls = MutableStateFlow<Set<String>>(emptySet())

    /** Current set of highlighted control IDs. */
    val highlightedControls: StateFlow<Set<String>> = _highlightedControls.asStateFlow()

    /** Replace the current highlights with a new set of control IDs. */
    fun highlight(controlIds: Set<String>) {
        log.debug { "Highlight controls: $controlIds" }
        _highlightedControls.value = controlIds
    }

    /** Clear all highlights. */
    fun clearHighlights() {
        log.debug { "Clear all highlights" }
        _highlightedControls.value = emptySet()
    }
}
