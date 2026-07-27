package org.balch.orpheus.core.features

import androidx.compose.ui.input.key.Key
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
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
@Inject
class FeatureCollection(
    private val providers: Map<KClass<*>, () -> SynthFeature<*, *>>,
) : AutoCloseable {
    private val log = logging("FeatureCollection")

    /**
     * Guards [cache].
     *
     * More than one thread reaches this map. The Compose UI tree resolves features as panels
     * compose, and on Android the DJ app's `MediaBrowserService` (Android Auto, lock screen)
     * resolves `PulsarFeature` from its own thread before the first playback command arrives.
     * A bare `getOrPut` lets both miss and both construct, which is the same duplicate-ViewModel
     * bug [FeatureGraphHolder] exists to prevent, one level further down: the second instance
     * would overwrite `MediaSessionManager`'s callback slots and the notification would drive a
     * feature the UI never observes.
     *
     * Reentrant on purpose. Building a feature can route back through an AppScope binding that
     * resolves another feature (`TimerFadeStatusProviderImpl` -> `TimerFeature` ->
     * `provideTimerFeature` -> `getFeature`), so a non-reentrant lock would deadlock. atomicfu's
     * [SynchronizedObject] is reentrant on JVM and Native, and compiles away on JS/Wasm.
     */
    private val lock = SynchronizedObject()
    private val cache = mutableMapOf<KClass<*>, SynthFeature<*, *>>()

    /** Get a typed feature, lazily created and cached. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getFeature(key: KClass<*>): T =
        synchronized(lock) {
            cache.getOrPut(key) {
                log.debug { "Creating feature for ${key.simpleName}" }
                val provider = providers[key]
                    ?: error("No SynthFeature provider registered for ${key.simpleName} (${key.qualifiedName})")
                provider()
            }
        } as T

    /** All features (triggers lazy creation of any not yet cached). */
    val allFeatures: Collection<SynthFeature<*, *>> by lazy {
        log.info { "FeatureCollection: creating all ${providers.size} features" }
        providers.keys.forEach { key ->
            try {
                getFeature<SynthFeature<*, *>>(key)
            } catch (e: Exception) {
                log.error(e) { "Failed to create feature for ${key.simpleName}" }
            }
        }
        // Snapshot under the lock. `cache.values` is a live view of the backing map, so handing
        // it out directly would let a later getFeature() mutate a collection someone is iterating.
        synchronized(lock) { cache.values.toList() }
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
        // Snapshot and clear under the lock, then close outside it. Feature teardown is arbitrary
        // user code (cancelling scopes, stopping audio) and holding the lock across it would block
        // any thread trying to resolve a feature for the duration.
        val toClose = synchronized(lock) {
            val snapshot = cache.values.toList()
            cache.clear()
            snapshot
        }
        toClose.filterIsInstance<AutoCloseable>().forEach {
            try { it.close() } catch (e: Exception) {
                log.warn { "Error closing feature: ${e.message}" }
            }
        }
    }
}
