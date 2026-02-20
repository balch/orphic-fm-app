package org.balch.orpheus.core

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
 * ViewModel wrapper that owns the [FeatureGraph] child scope.
 *
 * Lives in the ViewModelStore — `onCleared()` cancels the [FeatureCoroutineScope]
 * and closes [AutoCloseable] features.
 *
 * Panels access this via [LocalSynthFeatures]. AI tools inject [FeatureCollection] directly
 * (they live in [FeatureScope][org.balch.orpheus.core.di.FeatureScope]).
 */
@Inject
@ViewModelKey(SynthFeatureRegistry::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SynthFeatureRegistry(
    featureGraphFactory: FeatureGraph.Factory,
) : ViewModel() {
    private val featureGraph: FeatureGraph = featureGraphFactory.create()
    private val collection: FeatureCollection = featureGraph.featureCollection

    fun <T> getFeature(key: KClass<*>): T = collection.getFeature(key)

    val allFeatures: Collection<SynthFeature<*, *>> get() = collection.allFeatures

    val keyActions: Map<Key, List<KeyBinding>> get() = collection.keyActions

    override fun onCleared() {
        super.onCleared()
        featureGraph.featureCoroutineScope.cancel()
        collection.close()
    }
}

/** Typed convenience — usage: `registry.feature<VoiceViewModel, VoicesFeature>()` */
inline fun <reified VM : Any, reified F> SynthFeatureRegistry.feature(): F = getFeature(VM::class)
