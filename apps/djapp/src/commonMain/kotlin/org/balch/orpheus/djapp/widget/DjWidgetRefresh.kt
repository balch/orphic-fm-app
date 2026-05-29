package org.balch.orpheus.djapp.widget

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge

/**
 * Pure refresh pipeline for the DjApp home-screen widget: merge the state
 * sources, debounce bursts, and invoke [onRefresh] once per settled change.
 * Extracted from the Android `DjWidgetUpdater` so the merge/debounce behavior
 * is unit-testable without Android / Glance.
 */
object DjWidgetRefresh {
    const val DEBOUNCE_MS = 250L

    @OptIn(FlowPreview::class)
    suspend fun observe(
        sources: List<Flow<*>>,
        debounceMs: Long = DEBOUNCE_MS,
        onRefresh: suspend () -> Unit,
    ) {
        merge(*sources.toTypedArray()).debounce(debounceMs).collect { onRefresh() }
    }
}
