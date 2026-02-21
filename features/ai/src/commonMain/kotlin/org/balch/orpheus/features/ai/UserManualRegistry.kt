package org.balch.orpheus.features.ai

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.FeatureCollection
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.input.KeyBinding

/**
 * Registry that indexes [SynthFeature.SynthControl] contributions for lookup and search.
 *
 * Injected with the set of all registered features and provides
 * lookup-by-panel, search-by-query, and find-panel-for-control.
 */
@SingleIn(FeatureScope::class)
class UserManualRegistry @Inject constructor(
    private val featureCollection: FeatureCollection
) {
    private val features get() = featureCollection.allFeatures
    private val synthControls get() = features.map { it.synthControl }

    private val byPanelId: Map<PanelId, SynthFeature.SynthControl> by lazy {
        synthControls.associateBy { it.panelId }
    }

    /** panelId → keyBindings from the owning feature. */
    private val keyBindingsByPanel: Map<PanelId, List<KeyBinding>> by lazy {
        features.associate { it.synthControl.panelId to it.keyBindings }
    }

    private val controlToPanel: Map<String, PanelId> by lazy {
        buildMap {
            for (synthControl in synthControls) {
                for (controlId in synthControl.portControlKeys.keys) {
                    put(controlId, synthControl.panelId)
                }
            }
        }
    }

    /** Look up the manual for a specific panel. */
    fun lookup(panelId: PanelId): SynthFeature.SynthControl? = byPanelId[panelId]

    /** Look up the manual for a specific panel by its string ID. */
    fun lookup(panelIdString: String): SynthFeature.SynthControl? = lookup(PanelId(panelIdString))

    /** Find which panel owns a given controlId. */
    fun findPanelForControl(controlId: String): PanelId? = controlToPanel[controlId]

    /** Get the description of a specific control. */
    fun describeControl(controlId: String): String? {
        val panelId = controlToPanel[controlId] ?: return null
        return byPanelId[panelId]?.portControlKeys?.get(controlId)
    }

    /** Search manuals by query string (case-insensitive substring match). */
    fun search(query: String): List<SynthFeature.SynthControl> {
        val q = query.lowercase()
        return byPanelId.values.filter { manual ->
            manual.title.lowercase().contains(q) ||
                manual.markdown.lowercase().contains(q) ||
                manual.portControlKeys.any { (k, v) ->
                    k.lowercase().contains(q) || v.lowercase().contains(q)
                } ||
                keyBindingsFor(manual.panelId).any { binding ->
                    binding.label.lowercase().contains(q) ||
                        binding.description.lowercase().contains(q)
                }
        }
    }

    /** Format keyboard bindings for a panel as a markdown section. */
    fun formatKeyboardBindings(control: SynthFeature.SynthControl): String {
        val bindings = keyBindingsFor(control.panelId)
        if (bindings.isEmpty()) return ""
        return "\n\n**Keyboard Shortcuts:**\n" + bindings.joinToString("\n") { binding ->
            "- `${binding.label}`: ${binding.description}"
        }
    }

    /** Get all keyboard bindings across all features. */
    fun allKeyboardBindings(): List<Pair<String, List<KeyBinding>>> =
        synthControls
            .mapNotNull { ctrl ->
                val bindings = keyBindingsFor(ctrl.panelId)
                if (bindings.isNotEmpty()) ctrl.title to bindings else null
            }

    /** List all available panel IDs that have manuals. */
    val availablePanels: List<String>
        get() = byPanelId.keys.map { it.id }.sorted()

    private fun keyBindingsFor(panelId: PanelId): List<KeyBinding> =
        keyBindingsByPanel[panelId] ?: emptyList()
}
