package org.balch.orpheus.core.features

import androidx.compose.ui.input.key.Key
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.input.KeyBinding
import kotlin.reflect.KClass

/**
 * Typed index over the [SynthFeature] multibinding, keyed by each feature's public interface.
 *
 * Owns **no instances and holds no lock across construction**. Every ViewModel is
 * `@SingleIn(FeatureScope::class)`, so Metro already memoizes it; caching here would be a second
 * layer over the same objects, which is what used to deadlock -- this class held its lock across
 * `provider()` while a Metro `DoubleCheck` was taken in the opposite order on another thread.
 *
 * **Do not add a cache or a lock here.** A feature constructed twice means a missing
 * `@SingleIn(FeatureScope::class)`, not a missing cache; `FeatureScopeGuardTest` fails the build
 * for that, because Metro cannot -- an unscoped binding is legal and silently hands out fresh
 * instances.
 *
 * No `close()`: features live for the process, and process death is the cleanup point.
 */
@SingleIn(FeatureScope::class)
@Inject
class FeatureCollection(
    private val providers: Map<SynthFeatureKey, () -> SynthFeature<*, *>>,
) {
    private val log = logging("FeatureCollection")

    /**
     * Get a feature by its interface, which is also its [SynthFeatureKey], so the requested type
     * and the map key cannot drift. The cast is unchecked only because the multibinding erases.
     *
     * The *registration* is unchecked: `@SynthFeatureKey(DelayFeature::class)` on `ReverbViewModel`
     * compiles and throws `ClassCastException` on first resolve. Keying by interface moves that
     * mistake to one line instead of every call site; it does not eliminate it.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : SynthFeature<*, *>> getFeature(key: KClass<T>): T {
        val provider = byInterface[key]
            ?: error("No SynthFeature provider registered for ${key.simpleName} (${key.qualifiedName})")
        return provider() as T
    }

    /**
     * Interface -> provider. A necessary evil: the map key is the whole `@SynthFeatureKey` now,
     * so `providers[SomeFeature::class]` no longer type-checks. Built from keys alone, invokes
     * nothing.
     */
    private val byInterface: Map<KClass<out SynthFeature<*, *>>, () -> SynthFeature<*, *>> by lazy {
        providers.entries.associate { (key, provider) -> key.value to provider }
    }

    /**
     * Features flagged `startup = true`, constructed on the way out. The flag is on the map key, so
     * the filter runs before any provider does. Not lazy: a second call should re-run, not hide.
     */
    val startupFeatures: List<SynthFeature<*, *>>
        get() = providers.entries.filter { it.key.startup }.map { it.value() }
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
                getFeature(key.value)
            } catch (e: Exception) {
                log.error(e) { "Failed to create feature for ${key.value.simpleName}" }
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
