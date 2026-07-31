package org.balch.orpheus.core.features

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.input.KeyBinding


/**
 * A feature that exposes state as a StateFlow and stable actions.
 * Child composables should collect state at the leaf level for optimal recomposition.
 *
 * ## Sharing strategy
 *
 * Use [SynthFeature.sharingStrategy] (a single app-wide constant) when calling
 * `stateIn(...)` on the feature's [stateFlow]. There is no per-feature override:
 * `WhileSubscribed(5_000)` is the only acceptable strategy because [stateFlow]
 * must be a **pure projection** over authoritative state — typically a
 * `combine(...)` of `SynthController.controlFlow(...)` port flows plus any
 * UI-only `MutableStateFlow`s the feature owns.
 *
 * If you reach for `Eagerly`, you have side effects inside the `stateIn`
 * pipeline. Move them into always-on `scope.launch { ... }` collectors in the
 * `init {}` block — those run regardless of UI subscription and don't fight
 * the projection's lifecycle. See `EvoViewModel` for the canonical shape.
 */
interface SynthFeature<S, A> {
    val stateFlow: StateFlow<S>
    val actions: A

    val synthControl: SynthControl

    /**
     * Key bindings for this feature.
     * The keyboard handler collects these from all features to build the dispatch map,
     * and AI tools / documentation read them for shortcut descriptions (ignoring [action]).
     */
    val keyBindings: List<KeyBinding>
        get() = emptyList()

    companion object {
        /**
         * The single sharing strategy every SynthFeature must use.
         *
         * Held on the companion (not as an overridable interface property) so
         * features can't drift back to `Eagerly` to paper over architectural
         * coupling. If your feature feels like it needs hotter sharing, the
         * fix is moving the side effects out of the projection — not changing
         * this constant.
         */
        val sharingStrategy: SharingStarted = SharingStarted.WhileSubscribed(5_000)
    }

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
 * Usage: `val feature: LfoFeature = synthFeature<LfoFeature>()`
 *
 * The type argument is the feature interface, which is also its [SynthFeatureKey]. Naming it
 * once is what makes this safe: the older two-parameter form took the ViewModel class *and*
 * the return type as unrelated type parameters, so `synthFeature<LfoViewModel, DelayFeature>()`
 * compiled and threw `ClassCastException` at first composition. Asking for the wrong feature is
 * now a compile error.
 *
 * One case is still only caught at runtime: a ViewModel implements its feature interface, so it
 * satisfies the `SynthFeature<*, *>` bound and `synthFeature<LfoViewModel>()` compiles. It is not
 * the registered key, so it fails on first composition with a message naming the class. Always
 * name the **interface**, never the ViewModel.
 */
@Composable
inline fun <reified F : SynthFeature<*, *>> synthFeature(): F =
    LocalSynthFeatures.current.getFeature(F::class)
