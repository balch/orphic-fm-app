package org.balch.orpheus.core.coroutines

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Provides default implementations of [DispatcherProvider] to supply coroutine dispatchers.
 *
 * This class provides the standard coroutine dispatchers:
 *
 * - [Dispatchers.Main]: A coroutine dispatcher confined to the main thread for UI-related tasks.
 * - [Dispatchers.IO]: A coroutine dispatcher optimized for IO-bound operations like file and network access.
 * - [Dispatchers.Default]: A coroutine dispatcher optimized for CPU-intensive work.
 * - [Dispatchers.Unconfined]: A coroutine dispatcher that is not confined to any specific thread.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.Default
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
