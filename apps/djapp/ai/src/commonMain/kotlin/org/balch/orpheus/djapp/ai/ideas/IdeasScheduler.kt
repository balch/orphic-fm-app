package org.balch.orpheus.djapp.ai.ideas

import com.diamondedge.logging.logging
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * When the ideas strip refreshes: as each agent turn ends, and once [pauseMs] pass after the user's last mixer
 * move. Never while a turn runs, and one call at a time: triggers that land during a call run once after it,
 * with the latest input. [clock] reads milliseconds on the same timeline as [scope]'s delays.
 * Nothing is called while inactive: the strip is folded, so ideas are on demand ([setActive]).
 */
class IdeasScheduler(
    private val scope: CoroutineScope,
    private val generator: IdeasGenerator,
    private val clock: () -> Long,
    private val pauseMs: Long = 4_000,
    private var active: Boolean = false,
) {
    private val log = logging("IdeasScheduler")
    private val lock = SynchronizedObject()

    private val _ideas = MutableStateFlow<List<Idea>>(emptyList())
    val ideas: StateFlow<List<Idea>> = _ideas.asStateFlow()

    private var turnRunning = false
    private var lastMoveAtMs = 0L
    private var pauseInput: (() -> IdeasInput)? = null
    private var pauseJob: Job? = null
    private var calling = false
    private var callJob: Job? = null
    private var followUp: (() -> IdeasInput)? = null

    /**
     * Opening asks for a fresh set now, or at the running turn's end. Closing drops every pending refresh and
     * cancels the call in flight; the last ideas stay for the next open.
     */
    fun setActive(on: Boolean, input: () -> IdeasInput) = synchronized(lock) {
        if (on == active) return@synchronized
        active = on
        if (on) {
            if (!turnRunning) request(input)
        } else {
            dropPause()
            followUp = null
            callJob?.cancel()
            callJob = null
            calling = false
        }
    }

    /** Drops a pending pause refresh; a call already in flight still publishes. */
    fun onTurnStarted() = synchronized(lock) {
        turnRunning = true
        dropPause()
        followUp = null
    }

    fun onTurnEnded(input: IdeasInput) = onTurnEnded { input }

    /** As above, with [input] read when the call runs, so a failure to build it fails that call alone. */
    fun onTurnEnded(input: () -> IdeasInput) = synchronized(lock) {
        turnRunning = false
        dropPause()
        if (active) request(input)
    }

    /** A user move restarts the pause; [input] is read when the refresh runs. Moves during a turn wait for its end. */
    fun onUserMove(input: () -> IdeasInput) = synchronized(lock) {
        if (turnRunning || !active) return@synchronized
        lastMoveAtMs = clock()
        pauseInput = input
        if (pauseJob?.isActive != true) pauseJob = scope.launch { awaitPause() }
    }

    private fun dropPause() {
        pauseInput = null
        pauseJob?.cancel()
        pauseJob = null
    }

    private suspend fun awaitPause() {
        val self = currentCoroutineContext()[Job]
        while (true) {
            val wait = synchronized(lock) { lastMoveAtMs + pauseMs - clock() }
            if (wait <= 0) break
            delay(wait)
        }
        synchronized(lock) {
            // A turn may have dropped this wait and a later move started another.
            if (pauseJob !== self) return
            val input = pauseInput
            pauseInput = null
            pauseJob = null
            if (input != null && !turnRunning && active) request(input)
        }
    }

    /** Call under [lock]. */
    private fun request(input: () -> IdeasInput) {
        if (calling) {
            followUp = input
            return
        }
        calling = true
        callJob = scope.launch { runCalls(input) }
    }

    private suspend fun runCalls(first: () -> IdeasInput) {
        val self = currentCoroutineContext()[Job]
        var input = first
        try {
            while (true) {
                val fresh = generate(input)
                input = synchronized(lock) {
                    // A close cancelled this call; a reopen may already have started another.
                    if (callJob !== self) return
                    if (fresh.isNotEmpty()) _ideas.value = fresh
                    val next = followUp
                    followUp = null
                    if (next == null) {
                        calling = false
                        callJob = null
                    }
                    next
                } ?: return
            }
        } catch (e: CancellationException) {
            synchronized(lock) {
                if (callJob === self) {
                    calling = false
                    callJob = null
                    followUp = null
                }
            }
            throw e
        }
    }

    private suspend fun generate(input: () -> IdeasInput): List<Idea> =
        try {
            generator.ideas(input())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "Ideas refresh failed: ${e.message}" }
            emptyList()
        }
}
