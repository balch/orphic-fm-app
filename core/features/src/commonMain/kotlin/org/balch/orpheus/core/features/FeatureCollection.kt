package org.balch.orpheus.core.features

import androidx.compose.ui.input.key.Key
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.input.KeyBinding
import kotlin.reflect.KClass

/**
 * Feature-scoped container for all [SynthFeature] instances.
 *
 * Holds the DI-provided provider map, lazily creates features on first access,
 * and caches them. Lifecycle is managed by [SynthFeatureRegistry],
 * which calls [close] in its `onCleared()`.
 *
 * AI tools inject this directly for feature access without needing the ViewModel.
 */
@SingleIn(FeatureScope::class)
class FeatureCollection @Inject constructor(
    private val providers: Map<KClass<*>, Provider<SynthFeature<*, *>>>,
) : AutoCloseable {
    private val log = logging("FeatureCollection")
    private val cache = mutableMapOf<KClass<*>, SynthFeature<*, *>>()

    /** Get a typed feature, lazily created and cached. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getFeature(key: KClass<*>): T =
        cache.getOrPut(key) {
            log.debug { "Creating feature for ${key.simpleName}" }
            providers.getValue(key)()
        } as T

    /** All features (triggers lazy creation of any not yet cached). */
    val allFeatures: Collection<SynthFeature<*, *>> by lazy {
        log.info { "FeatureCollection: creating all ${providers.size} features" }
        providers.keys.forEach { getFeature<SynthFeature<*, *>>(it) }
        cache.values
    }

    /** Pre-built key action map from all features' key bindings. */
    val keyActions: Map<Key, List<KeyBinding>> by lazy {
        buildMap<Key, MutableList<KeyBinding>> {
            for (feature in allFeatures) {
                for (binding in feature.keyBindings) {
                    if (binding.action == null) continue
                    val list = getOrPut(binding.key) { mutableListOf() }

                    val conflict = list.any { it.requiresShift == binding.requiresShift }
                    if (conflict) {
                        log.warn {
                            "Key binding collision: ${binding.label} (requiresShift=${binding.requiresShift}) " +
                                "conflicts with existing binding for same key+shift combo"
                        }
                    }
                    list.add(binding)
                }
            }
        }
    }

    /** Close any [AutoCloseable] features and clear cache. */
    override fun close() {
        log.info { "FeatureCollection close — cleaning up features" }
        cache.values.filterIsInstance<AutoCloseable>().forEach {
            try { it.close() } catch (e: Exception) {
                log.warn { "Error closing feature: ${e.message}" }
            }
        }
        cache.clear()
    }
}
