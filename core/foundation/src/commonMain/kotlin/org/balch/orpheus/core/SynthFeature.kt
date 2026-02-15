package org.balch.orpheus.core

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.input.KeyBinding


/**
 * A feature that exposes state as a StateFlow and stable actions.
 * Child composables should collect state at the leaf level for optimal recomposition.
 */
interface SynthFeature<S, A> {
    val stateFlow: StateFlow<S>
    val actions: A

    val synthControl: SynthControl

    val sharingStrategy: SharingStarted
        get() = SharingStarted.WhileSubscribed(5_000)

    /**
     * Key bindings for this feature, with actions wired to the ViewModel.
     * The keyboard handler collects these from all features to build the dispatch map,
     * and AI tools / documentation read them for shortcut descriptions (ignoring [action]).
     */
    val keyBindings: List<KeyBinding>
        get() = emptyList()

    /**
     * Self-registering documentation descriptor for a feature panel.
     * Feature modules implement this interface and register via @ContributesIntoSet.
     *
     * The AI tools use [portControlKeys] (raw `PluginControlId.key` strings) directly
     * to control the synth — no mapping layer. The SynthControlTool builds its description
     * dynamically from the injected SynthControl set.
     */
    interface SynthControl {
        /** Which panel this manual documents. */
        val panelId: PanelId

        /** Human-readable title for this feature. */
        val title: String

        /** Markdown overview: what the feature does, how to use it, tips. */
        val markdown: String

        /**
         * Map of `PluginControlId.key` to a short human-readable description.
         * These are the actual port keys the AI uses to set synth parameters.
         */
        val portControlKeys: Map<String, String>

        companion object {
            val Empty = object : SynthControl {
                override val panelId = PanelId("EMPTY")
                override val title = "Empty"
                override val markdown = ""
                override val portControlKeys = emptyMap<String, String>()
            }
        }
    }
}

/** Extract a typed feature from a set. Throws if not found. */
inline fun <reified T : SynthFeature<*, *>> Set<SynthFeature<*, *>>.toFeature(): T =
    filterIsInstance<T>().first()

/** Extract a typed feature from a set, or null if not present. */
inline fun <reified T : SynthFeature<*, *>> Set<SynthFeature<*, *>>.toFeatureOrNull(): T? =
    filterIsInstance<T>().firstOrNull()

/**
 * Retrieve a ViewModel from the Metro DI graph, returning it as a feature.
 * Works like metroViewModel() but allows casting to a specific feature interface (e.g. BossFeature).
 *
 * Usage: `val feature: MyFeature = synthViewModel<MyViewModel, MyFeature>()`
 */
@Suppress("UNCHECKED_CAST")
@Composable
inline fun <reified VM : ViewModel, S, A> synthViewModel(): SynthFeature<S, A> =
    metroViewModel<VM>() as SynthFeature<S, A>


@Suppress("UNCHECKED_CAST")
@Composable
inline fun <reified VM : ViewModel, R> synthViewModel(): R =
    metroViewModel<VM>() as R
