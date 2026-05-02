package org.balch.orpheus.core.coroutines

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Shared app-lifetime coroutine scope. App-scoped components (PlaybackController,
 * MediaSessionStateManager, etc.) inject this instead of rolling their own
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` — making the
 * dispatcher swappable in tests via [DispatcherProvider] and centralizing
 * shutdown via [cancel] / [onCleared].
 *
 * Parallel to [org.balch.orpheus.core.features.FeatureCoroutineScope], which
 * lives at `FeatureScope` and is cancelled when the feature graph clears.
 */
@SingleIn(AppScope::class)
@Inject
class AppCoroutineScope(dispatcherProvider: DispatcherProvider) : CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext = job + dispatcherProvider.default
    fun cancel() { job.cancel() }
    fun onCleared(block: () -> Unit) { job.invokeOnCompletion { block() } }
}
