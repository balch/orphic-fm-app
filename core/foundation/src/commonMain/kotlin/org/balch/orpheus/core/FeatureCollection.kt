package org.balch.orpheus.core

import androidx.compose.ui.input.key.Key
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
) : SynchronizedObject(), AutoCloseable {
    private val log = logging("FeatureCollection")
    private val cache = mutableMapOf<KClass<*>, SynthFeature<*, *>>()
    private var closed = false

    /** Get a typed feature, lazily created and cached. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getFeature(key: KClass<*>): T = synchronized(this) {
        check(!closed) { "FeatureCollection is closed" }
        cache.getOrPut(key) {
            log.debug { "Creating feature for ${key.simpleName}" }
            providers[key]?.invoke()
                ?: error("No feature provider registered for ${key.simpleName}. Did you add @ContributesIntoMap(FeatureScope::class)?")
        } as T
    }

    /** All features (triggers lazy creation of any not yet cached). */
    val allFeatures: List<SynthFeature<*, *>> by lazy {
        log.info { "FeatureCollection: creating all ${providers.size} features" }
        providers.keys.forEach { getFeature<SynthFeature<*, *>>(it) }
        synchronized(this) { cache.values.toList() }
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

    /** Close any [AutoCloseable] features and clear cache. Idempotent. */
    override fun close(): Unit = synchronized(this) {
        if (closed) return
        closed = true
        log.info { "FeatureCollection close — cleaning up features" }
        cache.values.filterIsInstance<AutoCloseable>().forEach {
            try { it.close() } catch (e: Exception) {
                log.warn { "Error closing feature: ${e.message}" }
            }
        }
        cache.clear()
    }
}
