package org.balch.orpheus.core.features

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * App-scoped holder for the single shared [FeatureGraph].
 *
 * `FeatureGraph` is a Metro `@GraphExtension` — its factory produces a *new*
 * child graph (with its own copy of every `@SingleIn(FeatureScope::class)`
 * binding, including every feature ViewModel) on each `create()` call.
 *
 * For the Compose UI alone, that's fine: the Activity's `SynthFeatureRegistry`
 * calls the factory once and every panel sees the same ViewModels. But on
 * Android the DJ app's `MediaBrowserService` (Android Auto / lock-screen /
 * notification host) also needs feature access so it can register
 * `MediaSessionManager` callbacks before the first playback command arrives.
 * If the service called the factory itself, it would build a *second*
 * graph with a *second* `PulsarViewModel`. Since `MediaSessionManager` is
 * an `AppScope` singleton with `var`-slot callback handlers
 * (`onPlay`, `onPlayFromMediaId`, …), the second VM's `init` block would
 * silently overwrite the first VM's handlers — and from then on lock-screen
 * actions would mutate state on a VM the UI does not observe, producing
 * the symptom: notification & UI fall out of sync, and audio tracks the
 * notification's hidden VM rather than what's on screen.
 *
 * This holder eliminates the duplication: both the Activity-scoped registry
 * and any background service inject the holder and read [featureGraph],
 * which lazily builds the graph on first access and caches it for the life
 * of the process.
 */
@SingleIn(AppScope::class)
@Inject
class FeatureGraphHolder(
    factory: FeatureGraph.Factory,
) {
    val featureGraph: FeatureGraph by lazy { factory.create() }
}
