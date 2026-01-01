package org.balch.orpheus.core.coroutines

import kotlin.coroutines.cancellation.CancellationException

/**
 * Executes the given suspending block in the context of the receiver and returns a [Result] that
 * encapsulates the success or failure of the operation.
 *
 * This convenience method allows propagating [CancellationException]
 * up the call stack to adhere to Android best practices for
 * suspend functions.
 *
 * If the block completes successfully, its result is wrapped in a [Result.success].
 * If an exception occurs, it is caught and wrapped in a [Result.failure]. Note that
 * [CancellationException] is rethrown to respect coroutine cancellation.
 *
 * @param block The suspending block of code to execute.
 * @return A [Result] wrapping the outcome of the operation.
 */
suspend inline fun <T, R> T.runCatchingSuspend(
    block: suspend T.() -> R,
) = try {
        Result.success(block())
    } catch (ce : CancellationException) {
        throw ce
    } catch (e: Exception) {
        Result.failure(e)
    }