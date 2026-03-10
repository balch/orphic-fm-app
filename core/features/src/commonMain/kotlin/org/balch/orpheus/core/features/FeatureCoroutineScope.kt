package org.balch.orpheus.core.features

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.balch.orpheus.core.di.FeatureScope

/**
 * Shared coroutine scope for all [SynthFeature] instances.
 *
 * Replaces `viewModelScope` from ViewModel. Cancelled by [SynthFeatureRegistry.onCleared]
 * when the app's ViewModelStore is cleared.
 */
@SingleIn(FeatureScope::class)
@Inject
class FeatureCoroutineScope() : CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext = job + Dispatchers.Main.immediate
    fun cancel() { job.cancel() }

    /** Register a teardown callback that runs when the scope is cancelled. */
    fun onCleared(block: () -> Unit) {
        job.invokeOnCompletion { block() }
    }
}
