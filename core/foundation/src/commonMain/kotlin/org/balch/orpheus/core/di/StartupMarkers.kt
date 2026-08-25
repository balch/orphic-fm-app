package org.balch.orpheus.core.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Qualifier

/**
 * Qualifies the `AppScope` set of singletons whose constructor side effects ARE the point.
 *
 * ```kotlin
 * @ContributesIntoSet(AppScope::class, binding = binding<@StartupRoot Any>())
 * class PlaybackController(...) : MediaSessionActionHandler
 * ```
 *
 * Bound to `Any` so the annotation is the whole declaration -- a marker interface would be a second
 * place to forget. `TYPE` is needed so the qualifier can ride the `binding<...>` argument. Feature
 * ViewModels use `@SynthFeatureKey(..., startup = true)`; roots have no such annotation to widen.
 */
@Qualifier
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
annotation class StartupRoot

/** `allowEmpty` because an app may contribute no roots, which Metro otherwise treats as an error. */
@ContributesTo(AppScope::class)
interface StartupRootModule {
    @Multibinds(allowEmpty = true)
    @StartupRoot
    fun startupRoots(): Set<Any>
}
