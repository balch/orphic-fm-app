package org.balch.orpheus.core.coroutines

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * WASM dispatcher provider — maps all dispatchers to [Dispatchers.Main].
 *
 * WASM is single-threaded, so using [Dispatchers.Default] with [flowOn] introduces
 * unnecessary async dispatch that delays StateFlow updates, causing Compose to render
 * stale initial state before the scan reducer output propagates.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DefaultDispatcherProvider::class])
class WasmDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Main
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
