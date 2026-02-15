package org.balch.orpheus.features.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.input.KeyBinding
import kotlin.jvm.JvmSuppressWildcards

/**
 * Registry that indexes [SynthFeature.SynthControl] contributions for lookup and search.
 *
 * Injected with the set of all registered manuals and provides
 * lookup-by-panel, search-by-query, and find-panel-for-control.
 */
@SingleIn(AppScope::class)
class UserManualRegistry @Inject constructor(
    manuals: @JvmSuppressWildcards Set<SynthFeature<*, *>>
) {
    private val synthControls = manuals.map { it.synthControl }

    private val byPanelId: Map<PanelId, SynthFeature.SynthControl> =
        synthControls.associateBy { it.panelId }

    private val controlToPanel: Map<String, PanelId> = buildMap {
        for (synthControl in synthControls) {
            for (controlId in synthControl.portControlKeys.keys) {
                put(controlId, synthControl.panelId)
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
                manual.keyboardControlKeys.any { binding ->
                    binding.label.lowercase().contains(q) ||
                        binding.description.lowercase().contains(q)
                }
        }
    }

    /** Format keyboard bindings for a panel as a markdown section. */
    fun formatKeyboardBindings(control: SynthFeature.SynthControl): String {
        val bindings = control.keyboardControlKeys
        if (bindings.isEmpty()) return ""
        return "\n\n**Keyboard Shortcuts:**\n" + bindings.joinToString("\n") { binding ->
            "- `${binding.label}`: ${binding.description}"
        }
    }

    /** Get all keyboard bindings across all features. */
    fun allKeyboardBindings(): List<Pair<String, List<KeyBinding>>> =
        synthControls
            .filter { it.keyboardControlKeys.isNotEmpty() }
            .map { it.title to it.keyboardControlKeys }

    /** List all available panel IDs that have manuals. */
    val availablePanels: List<String>
        get() = byPanelId.keys.map { it.id }.sorted()
}
