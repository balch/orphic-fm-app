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
 * and caches them. Features are keyed by their public interface via [SynthFeatureKey].
 *
 * AI tools inject this directly for feature access without needing the ViewModel.
 *
 * Lifecycle: nothing calls [close] in production. [SynthFeatureRegistry] deliberately does not
 * override `onCleared()` (see its KDoc) because this collection is reached through an
 * `AppScope` holder that outlives the Activity, so process death is the cleanup point. [close]
 * is kept for tests and for any future owner with a real teardown point, and leaves this object
 * reusable: the next access rebuilds everything. Do not document it as something the registry
 * calls.
 */
@SingleIn(FeatureScope::class)
@Inject
class FeatureCollection(
    private val providers: Map<KClass<out SynthFeature<*, *>>, () -> SynthFeature<*, *>>,
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
    private val cache = mutableMapOf<KClass<out SynthFeature<*, *>>, SynthFeature<*, *>>()

    /**
     * Get a feature by its interface, lazily created and cached.
     *
     * [key] is the feature interface (`LfoFeature::class`), which is also its [SynthFeatureKey],
     * so the requested type and the map key cannot drift apart: `T` is pinned by [key]'s own
     * type argument, and a caller cannot ask for a type the key does not denote. The cast is
     * unchecked only because the multibinding erases to `SynthFeature<*, *>`.
     *
     * What is *not* checked is the registration. `@SynthFeatureKey(DelayFeature::class)` on
     * `ReverbViewModel` compiles, because both sides satisfy `KClass<out SynthFeature<*, *>>`,
     * and fails here with a `ClassCastException` on first resolve. Keying by interface moves
     * that mistake to the single line that declares it instead of every call site; it does not
     * eliminate it.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : SynthFeature<*, *>> getFeature(key: KClass<T>): T =
        synchronized(lock) {
            cache.getOrPut(key) {
                log.debug { "Creating feature for ${key.simpleName}" }
                val provider = providers[key]
                    ?: error("No SynthFeature provider registered for ${key.simpleName} (${key.qualifiedName})")
                provider()
            }
        } as T

    /**
     * Memoized results of [allFeatures] and [keyActions]. Guarded by [lock].
     *
     * Deliberately not `by lazy`: a `lazy` delegate computes once and holds that value for the
     * life of the object, so after a [close] it would keep handing back the *closed* instances
     * while [getFeature] correctly rebuilt fresh ones. These are nulled out in [close] instead.
     */
    private var allFeaturesCache: List<SynthFeature<*, *>>? = null
    private var keyActionsCache: Map<Key, List<KeyBinding>>? = null

    /** All features (triggers creation of any not yet cached). */
    val allFeatures: Collection<SynthFeature<*, *>>
        get() {
            synchronized(lock) { allFeaturesCache }?.let { return it }

            // Construct outside the lock so a concurrent getFeature() can still interleave;
            // getFeature() takes the lock per feature, so building all of them under one hold
            // would block the DJ app's MediaBrowserService thread for the whole batch.
            log.info { "FeatureCollection: creating all ${providers.size} features" }
            providers.keys.forEach { key ->
                try {
                    getFeature(key)
                } catch (e: Exception) {
                    log.error(e) { "Failed to create feature for ${key.simpleName}" }
                }
            }

            // Snapshot under the lock. `cache.values` is a live view of the backing map, so handing
            // it out directly would let a later getFeature() mutate a collection someone is iterating.
            // Two racing callers may both run the loop above; getFeature() is itself cached, so the
            // loser just re-reads cache hits and the first snapshot published wins.
            return synchronized(lock) {
                allFeaturesCache ?: cache.values.toList().also { allFeaturesCache = it }
            }
        }

    /** Pre-built key action map from all features' key bindings. */
    val keyActions: Map<Key, List<KeyBinding>>
        get() {
            synchronized(lock) { keyActionsCache }?.let { return it }

            val built = buildMap<Key, MutableList<KeyBinding>> {
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

            return synchronized(lock) {
                keyActionsCache ?: built.also { keyActionsCache = it }
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
            // Drop the memoized views too, or the next allFeatures/keyActions would hand back the
            // instances we are about to close while getFeature() rebuilt fresh ones.
            allFeaturesCache = null
            keyActionsCache = null
            snapshot
        }
        toClose.filterIsInstance<AutoCloseable>().forEach {
            try { it.close() } catch (e: Exception) {
                log.warn { "Error closing feature: ${e.message}" }
            }
        }
    }
}
