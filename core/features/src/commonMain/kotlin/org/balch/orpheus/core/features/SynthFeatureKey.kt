package org.balch.orpheus.core.features

import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/**
 * Map key for the [SynthFeature] multibinding, keyed by the feature's **public interface**.
 *
 * ```kotlin
 * @SynthFeatureKey(LfoFeature::class)
 * @ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
 * class LfoViewModel(...) : LfoFeature
 * ```
 *
 * Lookups name that same interface (`synthFeature<LfoFeature>()`), so key and returned type cannot
 * disagree. Narrower than Metro's `@ClassKey`, which would accept any class at all.
 *
 * `unwrapValue = false` makes the whole annotation the map key, so [startup] rides along and
 * `FeatureCollection` reads it without invoking any provider.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@MapKey(unwrapValue = false)
annotation class SynthFeatureKey(
    val value: KClass<out SynthFeature<*, *>>,
    /**
     * Build at startup instead of on first panel compose.
     *
     * Set it on any feature that restores ports or registers platform callbacks in `init {}` —
     * the multibinding is provider-valued, so those never run otherwise.
     */
    val startup: Boolean = false,
)
