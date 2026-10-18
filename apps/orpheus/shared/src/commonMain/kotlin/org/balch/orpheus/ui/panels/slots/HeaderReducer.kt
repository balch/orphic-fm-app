package org.balch.orpheus.ui.panels.slots

import kotlinx.serialization.json.Json
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.panels.PanelSet
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.PanelSlotsPrefs
import org.balch.orpheus.ui.FactoryPanelSets
import org.balch.orpheus.ui.panels.HeaderPanelUiState

internal sealed interface HeaderIntent {
    data class Open(val id: PanelId) : HeaderIntent
    data class Close(val id: PanelId) : HeaderIntent
    data class TogglePin(val id: PanelId) : HeaderIntent
    data class Touch(val id: PanelId) : HeaderIntent
    data class SetCapacity(val units: Int) : HeaderIntent
    data class SetRailVisible(val visible: Boolean) : HeaderIntent
    data class SetRecentsStripVisible(val visible: Boolean) : HeaderIntent
    data class ApplySet(val panelSet: PanelSet) : HeaderIntent
    data class Restore(
        val slots: PanelSlots,
        val railVisible: Boolean?,
        val recentsStripVisible: Boolean,
    ) : HeaderIntent
}

internal fun reduceHeader(state: HeaderPanelUiState, intent: HeaderIntent): HeaderPanelUiState =
    when (intent) {
        is HeaderIntent.Open -> state.copy(slots = state.slots.open(intent.id, ::isWidePanel))
        is HeaderIntent.Close -> state.copy(slots = state.slots.close(intent.id))
        is HeaderIntent.TogglePin -> state.copy(slots = state.slots.togglePin(intent.id))
        is HeaderIntent.Touch -> state.copy(slots = state.slots.touch(intent.id))
        is HeaderIntent.SetCapacity ->
            state.copy(slots = state.slots.withCapacity(intent.units, ::isWidePanel))
        is HeaderIntent.SetRailVisible -> state.copy(railVisible = intent.visible)
        is HeaderIntent.SetRecentsStripVisible -> state.copy(recentsStripVisible = intent.visible)
        is HeaderIntent.ApplySet -> state.copy(
            slots = state.slots.applySet(
                intent.panelSet.visibleIds.filter { it in intent.panelSet.expandedIds },
                ::isWidePanel,
            ),
            activePanelSet = intent.panelSet,
        )
        is HeaderIntent.Restore -> {
            // The UI waits for Restore, so capacity is normally 0 here; refit in case it is not.
            val capacity = state.slots.capacity
            val slots = if (capacity > 0) intent.slots.withCapacity(capacity, ::isWidePanel) else intent.slots
            state.copy(
                slots = slots,
                railVisible = intent.railVisible,
                recentsStripVisible = intent.recentsStripVisible,
                restored = true,
            )
        }
    }

/** Rebuilds slots from [prefs], else the legacy expansion JSON, else [defaultSet]. */
internal fun restoreSlots(
    prefs: AppPreferences,
    defaultSet: PanelSet,
    registered: Set<PanelId>,
): HeaderIntent.Restore {
    val saved = prefs.panelSlots
    if (saved != null) {
        val open = saved.open.toRegisteredIds(registered)
        val openSet = open.toSet()
        val slots = open.fold(PanelSlots()) { s, id -> s.open(id, ::isWidePanel) }.copy(
            lastUsed = (saved.lastUsed.toRegisteredIds(registered).filter { it in openSet } + open).distinct(),
            pinned = saved.pinned.toRegisteredIds(registered).filter { it in openSet }.toSet(),
            recents = saved.recents.toRegisteredIds(registered).filter { it !in openSet },
        )
        return HeaderIntent.Restore(slots, saved.railVisible, saved.recentsStripVisible)
    }

    val legacy = prefs.lastExpandedPanelsJson?.let(::decodeLegacyExpansion)
    val ids = if (legacy != null) {
        val expanded = legacy.filterValues { it }.keys.map(::PanelId).toSet()
        FactoryPanelSets.DesktopScreen.visibleIds.filter { it in expanded }
    } else {
        defaultSet.visibleIds.filter { it in defaultSet.expandedIds }
    }
    val slots = ids.filter { it in registered }.fold(PanelSlots()) { s, id -> s.open(id, ::isWidePanel) }
    return HeaderIntent.Restore(slots, railVisible = null, recentsStripVisible = true)
}

internal fun PanelSlots.toPrefs(railVisible: Boolean?, recentsStripVisible: Boolean) = PanelSlotsPrefs(
    open = openIds.map { it.id },
    lastUsed = lastUsed.map { it.id },
    pinned = openIds.filter { it in pinned }.map { it.id },
    recents = recents.map { it.id },
    railVisible = railVisible,
    recentsStripVisible = recentsStripVisible,
)

private fun List<String>.toRegisteredIds(registered: Set<PanelId>): List<PanelId> =
    map(::PanelId).filter { it in registered }.distinct()

private fun decodeLegacyExpansion(json: String): Map<String, Boolean>? =
    try {
        Json.decodeFromString<Map<String, Boolean>>(json).takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
