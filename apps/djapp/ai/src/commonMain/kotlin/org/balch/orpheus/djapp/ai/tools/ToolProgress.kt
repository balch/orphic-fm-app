package org.balch.orpheus.djapp.ai.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException

/** Lets a long tool report progress to whoever called it; absent for callers that can't show it. */
class ToolProgress(val report: suspend (done: Double, total: Double, message: String?) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolProgress>
}

/** Reports through the caller's [ToolProgress]; a no-op without one, and a failed report never fails the tool. */
suspend fun reportProgress(done: Double, total: Double, message: String? = null) {
    val progress = coroutineContext[ToolProgress] ?: return
    try {
        progress.report(done, total, message)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Progress is best effort: a closed stream must not cost the recording.
    }
}
