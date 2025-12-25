package org.balch.orpheus.core.coroutines

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
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.Default  // IO not available in WASM
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
