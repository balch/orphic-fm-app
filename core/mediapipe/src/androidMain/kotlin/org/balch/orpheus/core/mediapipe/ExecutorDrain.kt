package org.balch.orpheus.core.mediapipe

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Stops the executor taking work and waits, bounded, for the task in flight.
 * Returns false if that task is still running, so the caller must not free what it uses.
 */
internal fun ExecutorService.drain(timeoutMs: Long): Boolean {
    shutdown()
    return try {
        awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
