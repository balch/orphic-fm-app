package org.balch.orpheus.ui.widgets.engine

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice

/**
 * Search, chip and section model for one open picker. No UI, so common tests drive it directly.
 * [category] null means All; the Recent chip is its own flag, [recent], so leaving it restores the category.
 */
@Stable
class EnginePickerState(
    val choices: List<EngineChoice>,
    val current: EngineChoice,
    private val startCategory: EngineCategory?,
) {
    private val offered: Set<EngineChoice> = choices.toHashSet()

    /** Chips after Recent and All: the categories this slot offers, in enum order. */
    val categories: List<EngineCategory> = EngineCategory.entries.filter { c ->
        choices.any { EngineCatalog.category(it.engine) == c }
    }

    var query: String by mutableStateOf("")
    var category: EngineCategory? by mutableStateOf(openCategory())
    var recent: Boolean by mutableStateOf(false)

    /** Recent keys from every picker in the app; [visible] keeps only the ones this slot offers. */
    var recentKeys: List<String> by mutableStateOf(emptyList())

    /** The chip a fresh picker opens on: the slot's start category, else the current pick's, else All. */
    fun openCategory(): EngineCategory? =
        listOfNotNull(startCategory, EngineCatalog.category(current.engine)).firstOrNull { it in categories }

    fun selectCategory(category: EngineCategory?) {
        recent = false
        this.category = category
    }

    fun selectRecent() {
        recent = true
    }

    /** Section header to entries. A query searches every category, whichever chip is on. */
    val visible: List<Pair<String, List<EngineChoice>>> by derivedStateOf {
        val q = query.trim()
        when {
            q.isNotEmpty() -> group(EngineCatalog.search(choices, q))
            recent -> recentKeys
                .mapNotNull(EngineCatalog::fromKey)
                .filter { it in offered }
                .takeIf { it.isNotEmpty() }
                ?.let { listOf(RECENT_HEADER to it) }
                .orEmpty()
            else -> group(choices.filter { category == null || EngineCatalog.category(it.engine) == category })
        }
    }

    /** The first entry shown; null when nothing is. */
    fun firstVisible(): EngineChoice? = visible.firstOrNull()?.second?.firstOrNull()

    companion object {
        const val RECENT_HEADER = "Recent"

        /** Category label for an engine, `"<family> · <bank>"` for a DX patch. */
        fun headerFor(choice: EngineChoice): String = when (choice) {
            is EngineChoice.Patch -> "${choice.patch.family} · ${choice.patch.engine.name}"
            is EngineChoice.Engine -> EngineCatalog.category(choice.engine)?.label ?: "Other"
        }

        // groupBy keeps first-appearance order, so DX patch 19 joins Bass instead of splitting it.
        private fun group(entries: List<EngineChoice>) = entries.groupBy(::headerFor).toList()
    }
}
