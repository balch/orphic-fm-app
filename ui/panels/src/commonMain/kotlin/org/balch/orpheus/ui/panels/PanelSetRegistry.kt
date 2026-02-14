package org.balch.orpheus.ui.panels

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.balch.orpheus.core.panels.PanelSet

/**
 * In-memory registry of panel sets.
 * Starts empty; the app module seeds it with factory sets.
 */
@SingleIn(AppScope::class)
class PanelSetRegistry @Inject constructor() {

    private val log = logging("PanelSetRegistry")

    private val _allSets = MutableStateFlow<List<PanelSet>>(emptyList())
    val allSets: StateFlow<List<PanelSet>> = _allSets.asStateFlow()

    private val _activePanelSet = MutableStateFlow(PanelSet(name = "All"))
    val activePanelSet: StateFlow<PanelSet> = _activePanelSet.asStateFlow()

    fun getByName(name: String): PanelSet? =
        _allSets.value.find { it.name.equals(name, ignoreCase = true) }

    fun add(panelSet: PanelSet) {
        log.debug { "Adding panel set: ${panelSet.name}" }
        _allSets.update { sets ->
            // Replace existing with same name, or append
            val existing = sets.indexOfFirst { it.name.equals(panelSet.name, ignoreCase = true) }
            if (existing >= 0) {
                sets.toMutableList().apply { set(existing, panelSet) }
            } else {
                sets + panelSet
            }
        }
    }

    fun delete(name: String) {
        val set = getByName(name)
        if (set == null) {
            log.warn { "Panel set not found: $name" }
            return
        }
        if (set.isFactory) {
            log.warn { "Cannot delete factory panel set: $name" }
            return
        }
        log.debug { "Deleting panel set: $name" }
        _allSets.update { sets -> sets.filter { !it.name.equals(name, ignoreCase = true) } }
        // If the deleted set was active, fall back to All
        if (_activePanelSet.value.name.equals(name, ignoreCase = true)) {
            activate("All")
        }
    }

    fun activate(name: String) {
        val set = getByName(name)
        if (set == null) {
            log.warn { "Panel set not found: $name" }
            return
        }
        log.debug { "Activating panel set: ${set.name}" }
        _activePanelSet.value = set
    }

    /** Seed the registry with initial sets and activate one. */
    fun seed(sets: List<PanelSet>, activeSetName: String = "All") {
        _allSets.value = sets
        activate(activeSetName)
    }
}
