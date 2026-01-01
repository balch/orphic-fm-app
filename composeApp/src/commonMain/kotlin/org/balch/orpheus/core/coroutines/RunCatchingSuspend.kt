package org.balch.orpheus.core.coroutines

import kotlin.coroutines.cancellation.CancellationException

suspend inline fun <T, R> T.runCatchingSuspend(
    block: suspend T.() -> R,
) = try {
        Result.success(block())
    } catch (ce : CancellationException) {
        throw ce
    } catch (e: Exception) {
        Result.failure(e)
    }