package org.balch.orpheus.core.features

import androidx.compose.ui.input.key.Key
import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import org.balch.orpheus.core.input.KeyBinding
import kotlin.reflect.KClass

/**
 * Thin `ViewModel`-shaped accessor for the app-scoped [FeatureGraph].
 *
 * Lives in the `ViewModelStore` so Compose can look it up via
 * [LocalSynthFeatures]. The actual graph and feature instances are owned
 * by [FeatureGraphHolder] (`@SingleIn(AppScope::class)`) so multiple
 * consumers — the Activity's UI tree *and* any Android background service
 * (`MediaBrowserService`) — share the exact same `FeatureGraph` and the
 * exact same feature ViewModels.
 *
 * Lifecycle note: this class deliberately does **not** override
 * `onCleared()`. The graph it exposes outlives the Activity (it's
 * AppScope-scoped) and tearing down its `featureCoroutineScope` or closing
 * its `AutoCloseable` features here would yank state out from under any
 * background service still using them. Process death is the cleanup point.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SynthFeatureRegistry(
    holder: FeatureGraphHolder,
) : ViewModel() {
    private val collection: FeatureCollection = holder.featureGraph.featureCollection

    fun <T : SynthFeature<*, *>> getFeature(key: KClass<T>): T = collection.getFeature(key)

    val allFeatures: Collection<SynthFeature<*, *>> get() = collection.allFeatures

    val keyActions: Map<Key, List<KeyBinding>> get() = collection.keyActions
}

/** Typed convenience — usage: `registry.feature<VoicesFeature>()` */
inline fun <reified F : SynthFeature<*, *>> SynthFeatureRegistry.feature(): F = getFeature(F::class)
