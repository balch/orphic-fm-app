package org.balch.orpheus.features.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/**
 * Per-turn caps the agent loop cannot talk its way past; reset at each user turn.
 * Counters are atomic because one model reply can run several tool calls in parallel.
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
@Inject
class TurnBudget {
    private val applies = AtomicInt(0)
    private val mixEdits = AtomicInt(0)
    private val rejections = AtomicInt(0)

    fun reset() {
        applies.store(0)
        mixEdits.store(0)
        rejections.store(0)
    }

    /** Spends one vibe apply; false once [MAX_APPLIES] are spent this turn. */
    fun tryApply(): Boolean = applies.addAndFetch(1) <= MAX_APPLIES

    /** Spends one mix edit; false once [MAX_MIX_EDITS] are spent this turn. */
    fun tryMixEdit(): Boolean = mixEdits.addAndFetch(1) <= MAX_MIX_EDITS

    /** Counts one rejected vibe; false once more than [MAX_REJECTED_APPLIES] were rejected this turn. */
    fun tryReject(): Boolean = rejections.addAndFetch(1) <= MAX_REJECTED_APPLIES

    /** [tryReject], except that a call running under [Exempt] is always allowed and never counted. */
    suspend fun allowReject(): Boolean = isExempt() || tryReject()

    /** [tryApply], except that a call running under [Exempt] is always allowed and never counted. */
    suspend fun allowApply(): Boolean = isExempt() || tryApply()

    /** [tryMixEdit], except that a call running under [Exempt] is always allowed and never counted. */
    suspend fun allowMixEdit(): Boolean = isExempt() || tryMixEdit()

    private suspend fun isExempt() = currentCoroutineContext()[Exempt] != null

    /** Marks a tool call made outside any agent turn (MCP), where nothing would ever reset the budget. */
    object Exempt : CoroutineContext.Element, CoroutineContext.Key<Exempt> {
        override val key: CoroutineContext.Key<*> get() = this
    }

    companion object {
        const val MAX_APPLIES = 3
        const val MAX_MIX_EDITS = 12
        /** Rejected vibes the agent may fix and resend in one turn before it must stop and tell the user. */
        const val MAX_REJECTED_APPLIES = 3
        const val SPENT = "This turn's budget is spent: reply to the user now and let them listen."
    }
}
