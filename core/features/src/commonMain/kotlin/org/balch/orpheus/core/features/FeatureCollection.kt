package org.balch.orpheus.core.features

import androidx.compose.ui.input.key.Key
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.input.KeyBinding
import kotlin.reflect.KClass

/**
 * Typed index over the [SynthFeature] multibinding, keyed by each feature's public interface
 * via [SynthFeatureKey].
 *
 * This class owns **no feature instances and holds no lock across feature construction**.
 * Every feature ViewModel is
 * `@SingleIn(FeatureScope::class)`, so Metro memoizes it in a provider field on the generated
 * `FeatureGraphImpl` and `providers[key]()` returns that one instance. Caching here as well would
 * be a second memoization layer over the same objects, which is exactly what used to deadlock:
 * this class held its lock across `provider()` -- arbitrary ViewModel construction that re-enters
 * DI -- while a Metro `DoubleCheck` was taken in the opposite order on another thread.
 *
 * Metro's own memoization does not have that problem. Its locks are per binding, it never holds
 * one across a call it cannot see, and it proves the binding graph acyclic at compile time. So
 * instance identity has exactly one owner, and it is the DI container.
 *
 * **Do not add a cache or a lock here.** If a feature is being constructed more than once, the
 * bug is a missing `@SingleIn(FeatureScope::class)` on that ViewModel, not a missing cache.
 * `FeatureScopeGuardTest` fails the build for that, because Metro cannot: an unscoped binding is
 * perfectly legal, it just silently hands out a fresh instance per resolve.
 *
 * There is deliberately no `close()`. These features live for the process; the graph is reached
 * through an `AppScope` holder that outlives the Activity, and process death is the cleanup point.
 *
 * AI tools inject this directly for feature access without needing the ViewModel.
 */
@SingleIn(FeatureScope::class)
@Inject
class FeatureCollection(
    private val providers: Map<KClass<out SynthFeature<*, *>>, () -> SynthFeature<*, *>>,
) {
    private val log = logging("FeatureCollection")

    /**
     * Get a feature by its interface.
     *
     * [key] is the feature interface (`LfoFeature::class`), which is also its [SynthFeatureKey],
     * so the requested type and the map key cannot drift apart: `T` is pinned by [key]'s own type
     * argument. The cast is unchecked only because the multibinding erases to `SynthFeature<*, *>`.
     *
     * What is *not* checked is the registration. `@SynthFeatureKey(DelayFeature::class)` on
     * `ReverbViewModel` compiles, because both sides satisfy `KClass<out SynthFeature<*, *>>`, and
     * fails here with a `ClassCastException` on first resolve. Keying by interface moves that
     * mistake to the single line that declares it instead of every call site; it does not
     * eliminate it.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : SynthFeature<*, *>> getFeature(key: KClass<T>): T {
        val provider = providers[key]
            ?: error("No SynthFeature provider registered for ${key.simpleName} (${key.qualifiedName})")
        return provider() as T
    }

    /**
     * All features, in multibinding order. Resolves each through Metro, so all are singletons.
     *
     * [LazyThreadSafetyMode.PUBLICATION], not the default SYNCHRONIZED. The default holds the
     * lazy's monitor across every `provider()` call, which is a lock held across arbitrary
     * ViewModel construction, the exact shape this class exists to avoid. Racing initializers
     * are harmless here: the providers are Metro-memoized, so a duplicate run resolves the same
     * singletons, and the first published list wins.
     */
    val allFeatures: Collection<SynthFeature<*, *>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        log.info { "FeatureCollection: resolving all ${providers.size} features" }
        providers.keys.mapNotNull { key ->
            try {
                getFeature(key)
            } catch (e: Exception) {
                log.error(e) { "Failed to create feature for ${key.simpleName}" }
                null
            }
        }
    }

    /**
     * Pre-built key action map from all features' key bindings.
     * PUBLICATION for the same reason as [allFeatures]: no monitor held across construction.
     */
    val keyActions: Map<Key, List<KeyBinding>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
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
}
