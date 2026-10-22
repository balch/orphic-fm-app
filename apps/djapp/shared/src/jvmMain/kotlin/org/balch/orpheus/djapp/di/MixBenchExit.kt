package org.balch.orpheus.djapp.di

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.features.pulsar.bench.MixBenchController

/**
 * Stops the AppScope [MixBenchController] once at app exit, from the window close or the bench's own hook,
 * so the meter poll and the tap stop before the engine closes.
 */
class MixBenchExit(private val controller: MixBenchController) {
    private val done = AtomicBoolean(false)

    /** Stops the controller, blocking up to [timeoutMs] so a wedged lock can't hang exit. False on timeout. */
    fun run(timeoutMs: Long = 2_000): Boolean {
        if (!done.compareAndSet(false, true)) return true
        return runBlocking(Dispatchers.Default) { withTimeoutOrNull(timeoutMs) { controller.stop() } } != null
    }

    companion object {
        /** The exit for the controller the graph built, or null while nothing has asked for one. */
        @Volatile
        var current: MixBenchExit? = null
            private set

        /** Makes [controller]'s exit [current], for [runIfCreated]. */
        fun register(controller: MixBenchController): MixBenchExit = MixBenchExit(controller).also { current = it }

        /** Runs [current]'s exit if a controller was ever built; true when there was none. */
        fun runIfCreated(): Boolean = current?.run() ?: true
    }
}

internal actual fun stopOnAppExit(controller: MixBenchController) {
    MixBenchExit.register(controller)
}
