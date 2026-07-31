package org.balch.orpheus.core.features

import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/**
 * Map key for the [SynthFeature] multibinding, keyed by the feature's **public interface**.
 *
 * Register a ViewModel under the interface its panel consumes, not under the ViewModel class:
 *
 * ```kotlin
 * @Inject
 * @SynthFeatureKey(LfoFeature::class)
 * @ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
 * class LfoViewModel(...) : LfoFeature
 * ```
 *
 * Lookups then name that same interface once (`synthFeature<LfoFeature>()`), so the map key
 * and the returned type are the same symbol and cannot disagree.
 *
 * This replaces Metro's built-in `@ClassKey`, which is typed `KClass<*>` and would accept any
 * class at all as a key. Constraining the parameter to `KClass<out SynthFeature<*, *>>` is the
 * approach Metro's own `ClassKey` KDoc recommends when a map's keys can be narrowed, and it
 * makes registering a non-feature type a compile error rather than an entry nothing can resolve.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@MapKey
annotation class SynthFeatureKey(val value: KClass<out SynthFeature<*, *>>)
