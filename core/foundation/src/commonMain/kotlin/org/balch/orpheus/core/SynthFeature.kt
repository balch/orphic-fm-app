package org.balch.orpheus.core

import androidx.compose.runtime.Composable
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
     * Key bindings for this feature.
     * The keyboard handler collects these from all features to build the dispatch map,
     * and AI tools / documentation read them for shortcut descriptions (ignoring [action]).
     */
    val keyBindings: List<KeyBinding>
        get() = emptyList()

    /**
     * Self-registering documentation descriptor for a feature panel.
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

/**
 * Retrieve a feature from the [SynthFeatureRegistry] via [LocalSynthFeatures].
 * Used as default parameter values in panel composables.
 *
 * Usage: `val feature: MyFeature = synthFeature<MyViewModel, MyFeature>()`
 */
@Suppress("UNCHECKED_CAST")
@Composable
inline fun <reified F : Any, S, A> synthFeature(): SynthFeature<S, A> =
    LocalSynthFeatures.current.getFeature<SynthFeature<S, A>>(F::class)

@Suppress("UNCHECKED_CAST")
@Composable
inline fun <reified F : Any, R> synthFeature(): R =
    LocalSynthFeatures.current.getFeature<R>(F::class)
