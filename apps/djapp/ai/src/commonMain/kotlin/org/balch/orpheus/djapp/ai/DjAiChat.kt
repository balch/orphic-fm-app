package org.balch.orpheus.djapp.ai

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.djapp.ai.collab.CollabEvent
import org.balch.orpheus.djapp.ai.collab.DjVibeApplyGuard
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.djapp.ai.collab.MixMove
import org.balch.orpheus.djapp.ai.collab.label
import org.balch.orpheus.djapp.ai.collab.turnContext
import org.balch.orpheus.features.ai.AgentActivityEvent
import org.balch.orpheus.features.ai.PromptIntent
import org.balch.orpheus.features.pulsar.VibeCreateEvent
import org.balch.orpheus.features.pulsar.bench.BenchMeters

private const val SONG_REFERENCE_SHOWN = "♪ song reference"
private const val STOPPED = "Stopped."
private const val OPEN_MIXER_CARD = "Open the Mixer so I can make that change."
private const val MIXER_IS_OPEN = "The Mixer is open."
private const val MIXER_STILL_CLOSED = "The Mixer is still closed."
private const val CLEAR_AND_APPLY = "Yes, clear my mix and apply."
private const val KEEP_MY_MIX = "Keep my mix; don't re-apply."
private const val RECAP_MESSAGES = 12
private const val RECAP_CHARS = 300

/** How long an Open Mixer accept waits for the Mixer to show before telling the agent it did not. */
internal const val MIXER_WAIT_MS = 2_000L

/** What the chat's buttons send; a stopped turn drops these instead of handing them back to the input. */
private val BUTTON_MESSAGES = setOf(MIXER_IS_OPEN, MIXER_STILL_CLOSED, CLEAR_AND_APPLY, KEEP_MY_MIX)

/** The Mixer's own strip names, so feed rows and the agent's context name strips as the Mixer does. */
internal fun mixerTrackName(track: Int): String = BenchMeters.CHANNEL_NAMES.getOrElse(track) { "T$track" }

private fun DjAiUiState.append(make: (id: Long) -> DjAiFeedItem): DjAiUiState =
    copy(feed = feed + make(nextId), nextId = nextId + 1)

/** A turn starts: [shown] joins the feed as its Request row and the one-turn flags are spent. */
internal fun startTurn(s: DjAiUiState, shown: String): DjAiUiState =
    s.append { DjAiFeedItem.Request(it, shown) }.copy(
        phase = DjAiPhase.GENERATING,
        error = null,
        recapNext = false,
        confirmWipeNext = false,
    )

/**
 * The turn stopped and the agent's history went with it: back to IDLE, messages the user typed while it ran go
 * back into the input, and the song reference is announced again on the next send.
 */
internal fun stopTurn(s: DjAiUiState): DjAiUiState = s.copy(
    phase = DjAiPhase.IDLE,
    queued = emptyList(),
    promptDraft = (s.queued.filterNot { it in BUTTON_MESSAGES } + s.promptDraft).filter { it.isNotBlank() }.joinToString("\n\n"),
    confirmWipeNext = false,
    referenceSentId = null,
)

/** The last [RECAP_MESSAGES] messages of [feed] as "You:" and "Agent:" lines, each cut to [RECAP_CHARS]. */
internal fun conversationRecap(feed: List<DjAiFeedItem>): String =
    feed.mapNotNull {
        when (it) {
            is DjAiFeedItem.Request -> "You: ${it.text.take(RECAP_CHARS)}"
            is DjAiFeedItem.Reply -> "Agent: ${it.text.take(RECAP_CHARS)}"
            else -> null
        }
    }.takeLast(RECAP_MESSAGES).joinToString("\n", prefix = "[Earlier in this conversation]\n")

/**
 * A mixer move joins the feed as a [DjAiFeedItem.Move] row; a move on the same control by the same hand as the
 * last row updates that row, so a drag is one row, and one that ends where it began leaves none. The agent's
 * moves show only while its turn runs.
 */
internal fun withMove(s: DjAiUiState, move: MixMove, label: String): DjAiUiState {
    if (move.origin == EditOrigin.AGENT && s.phase == DjAiPhase.IDLE) return s
    val tail = s.feed.lastOrNull()
    if (tail is DjAiFeedItem.Move && tail.origin == move.origin && tail.label == label) {
        val rest = s.feed.dropLast(1)
        return s.copy(feed = if (move.after == tail.before) rest else rest + tail.copy(after = move.after))
    }
    if (move.before == move.after) return s
    return s.append { DjAiFeedItem.Move(it, move.origin, label, move.before, move.after) }
}

/** Adds an [DjAiFeedItem.Action] card unless this turn (since the last Request row) already shows one of [kind]. */
internal fun withActionOncePerTurn(s: DjAiUiState, kind: ActionKind, text: String): DjAiUiState {
    val turn = s.feed.drop(s.feed.indexOfLast { it is DjAiFeedItem.Request } + 1)
    if (turn.any { it is DjAiFeedItem.Action && it.kind == kind }) return s
    return s.append { DjAiFeedItem.Action(it, kind, text) }
}

/** Whether the input's send would go: text, a paste still parsing, or a song reference the agent has not been told about. */
internal fun DjAiUiState.canSend(): Boolean =
    isKeySet && (promptDraft.isNotBlank() || parsing != null || (reference != null && reference.id != referenceSentId))

/** Applies [step] atomically and returns what the winning attempt decided; [step] may run more than once. */
private inline fun <T> MutableStateFlow<DjAiUiState>.transition(step: (DjAiUiState) -> Pair<DjAiUiState, T>): T {
    while (true) {
        val s = value
        val (next, out) = step(s)
        if (compareAndSet(s, next)) return out
    }
}

/**
 * The conversation: sends and the queue, cancel, model switches, and the mixer moves and tool questions that
 * join the feed. Turn starts and ends hold [turns], so a send cannot land between a turn's end and its
 * `endTurn()`; rows that only add to the feed are atomic state steps and need no lock. [onTurnStarted] and
 * [onTurnEnded] run under that lock, so they arrive in order; they must not block.
 */
internal class DjAiChat(
    private val state: MutableStateFlow<DjAiUiState>,
    private val collab: MixCollab,
    private val scope: CoroutineScope,
    private val sendPrompt: (PromptIntent) -> Unit,
    private val restartAgent: () -> Unit,
    private val trackName: (Int) -> String = ::mixerTrackName,
    private val onTurnStarted: () -> Unit = {},
    private val onTurnEnded: () -> Unit = {},
) {
    private val turns = SynchronizedObject()

    /** A turn to open: the state before its Request row, the message, and a reference the agent has not been told about. */
    private class Start(val base: DjAiUiState, val text: String, val reference: SongPasteRef?, val label: String?)

    private class TurnEnd(val next: Start?)

    /**
     * Sends [text] as the user's next message, or queues it while a turn runs. [clearDraft] when it came from the
     * input; [confirmWipe] carries the user's "clear my mix" into the turn that sends it.
     * [label] names the turn's History point; it defaults to [text].
     */
    fun send(
        text: String,
        reference: SongPasteRef?,
        clearDraft: Boolean = false,
        confirmWipe: Boolean = false,
        label: String? = null,
    ) {
        synchronized(turns) {
            val start = state.transition { s0 ->
                if (text.isBlank() && fresh(s0, reference) == null) return@transition s0 to null
                val s = (if (clearDraft) s0.copy(promptDraft = "", lastPrompt = text) else s0)
                    .copy(confirmWipeNext = s0.confirmWipeNext || confirmWipe)
                when {
                    s.phase == DjAiPhase.IDLE -> begin(s, text, reference, label)
                    text.isBlank() -> s0 to null
                    else -> s.copy(queued = s.queued + text) to null
                }
            } ?: return
            dispatch(start)
        }
    }

    fun onActivity(e: AgentActivityEvent) {
        if (e !is AgentActivityEvent.Assistant && e !is AgentActivityEvent.Error) {
            state.update { reduceActivityEvent(e, it) }
            return
        }
        synchronized(turns) {
            val end = state.transition { s ->
                val reduced = reduceActivityEvent(e, s)
                if (s.phase == DjAiPhase.IDLE) return@transition reduced to null
                val ended = reduced.copy(phase = DjAiPhase.IDLE)
                // The run died with its history: the next prompt recaps, and queued messages wait in the input.
                if (e is AgentActivityEvent.Error) return@transition stopTurn(ended).copy(recapNext = true) to TurnEnd(null)
                if (ended.queued.isEmpty()) return@transition ended to TurnEnd(null)
                val (next, start) = begin(ended.copy(queued = emptyList()), ended.queued.joinToString("\n\n"), ended.reference)
                next to TurnEnd(start)
            } ?: return
            collab.endTurn()
            val next = end.next
            if (next != null) dispatch(next) else onTurnEnded()
        }
    }

    /** Reduces [e]; true when the vibe should start playing: the in-app turn's apply, or one made outside any turn. */
    fun onVibeEvent(e: VibeCreateEvent): Boolean {
        val before = state.getAndUpdate { reduceVibeEvent(e, it) }
        return e is VibeCreateEvent.Generated && (before.phase != DjAiPhase.IDLE || e.outsideTurn)
    }

    fun onMove(move: MixMove) = state.update { withMove(it, move, move.control.label(trackName)) }

    fun onCollabEvent(e: CollabEvent) = state.update { s ->
        when (e) {
            is CollabEvent.OpenMixerRequested -> withActionOncePerTurn(s, ActionKind.OPEN_MIXER, OPEN_MIXER_CARD)
            is CollabEvent.WipeConfirmRequested ->
                withActionOncePerTurn(s, ActionKind.CONFIRM_WIPE, "This clears your mix: ${DjVibeApplyGuard.changes(e.changes)}.")
            is CollabEvent.AgentSkipped -> s.append {
                DjAiFeedItem.Notice(it, "You moved ${e.control.label(trackName)} while I was working, so I left it at your ${e.userValue}.")
            }
        }
    }

    fun onAction(id: Long, accept: Boolean) {
        val card = state.transition { s ->
            val i = s.feed.indexOfFirst { it.id == id && it is DjAiFeedItem.Action && !it.resolved }
            if (i < 0) return@transition s to null
            val c = s.feed[i] as DjAiFeedItem.Action
            val resolved = s.copy(feed = s.feed.toMutableList().also { it[i] = c.copy(answer = accept) })
            val opens = accept && c.kind == ActionKind.OPEN_MIXER
            (if (opens) resolved.copy(sheetMode = AiSheetMode.MIXER) else resolved) to c
        } ?: return
        when (card.kind) {
            ActionKind.OPEN_MIXER -> if (accept) whenMixerShows { open -> send(if (open) MIXER_IS_OPEN else MIXER_STILL_CLOSED, null) }
            ActionKind.CONFIRM_WIPE -> if (accept) send(CLEAR_AND_APPLY, null, confirmWipe = true) else send(KEEP_MY_MIX, null)
        }
    }

    /** Stops the running turn. The feed stays, and the next prompt recaps it, since a restart drops the agent's history. */
    fun cancel() {
        synchronized(turns) {
            val stopped = state.transition { s ->
                if (s.phase == DjAiPhase.IDLE) s to false
                else stopTurn(s).append { DjAiFeedItem.Notice(it, STOPPED) }.copy(recapNext = true) to true
            }
            if (!stopped) return
            restartAgent()
            collab.endTurn()
            onTurnEnded()
        }
    }

    /** A new model starts a new conversation under a divider, with no recap. */
    fun switchModel(modelName: String) {
        synchronized(turns) {
            val before = state.getAndUpdate { s ->
                val switched = applyModelSwitch(stopTurn(s)).copy(recapNext = false)
                if (s.feed.isEmpty()) switched else switched.append { DjAiFeedItem.Divider(it, "New conversation with $modelName") }
            }
            restartAgent()
            collab.endTurn()
            if (before.phase != DjAiPhase.IDLE) onTurnEnded()
        }
    }

    fun newConversation() {
        synchronized(turns) {
            val before = state.getAndUpdate {
                clearRunOutput(it).copy(
                    phase = DjAiPhase.IDLE,
                    queued = emptyList(),
                    recapNext = false,
                    confirmWipeNext = false,
                    referenceSentId = null,
                )
            }
            restartAgent()
            collab.endTurn()
            if (before.phase != DjAiPhase.IDLE) onTurnEnded()
        }
    }

    /** [reference] when the agent has not been told about it in this conversation. */
    private fun fresh(s: DjAiUiState, reference: SongPasteRef?) = reference?.takeIf { it.id != s.referenceSentId }

    private fun begin(s: DjAiUiState, text: String, reference: SongPasteRef?, label: String? = null): Pair<DjAiUiState, Start> {
        val announce = fresh(s, reference)
        val next = startTurn(s.copy(referenceSentId = announce?.id ?: s.referenceSentId), text.ifBlank { SONG_REFERENCE_SHOWN })
        return next to Start(s, text, announce, label)
    }

    /** Runs [block] with true once the Mixer is on screen, or with false after [MIXER_WAIT_MS] if it never shows. */
    private fun whenMixerShows(block: (open: Boolean) -> Unit) {
        if (collab.mixerVisible.value) return block(true)
        scope.launch {
            withTimeoutOrNull(MIXER_WAIT_MS) { collab.mixerVisible.first { it } }
            block(collab.mixerVisible.value)
        }
    }

    /** Opens the turn: the Mixer state and the user's moves go first, then the message as written. */
    private fun dispatch(start: Start) {
        val base = start.base
        val moves = collab.takeMovesForAgent()
        collab.beginTurn((start.label ?: start.text).ifBlank { null })
        onTurnStarted()
        // beginTurn clears any confirmation, so the user's comes after it.
        if (base.confirmWipeNext) collab.confirmWipe()
        val pinned = collab.pins.value.sorted().map(trackName)
        val context = turnContext(moves, collab.mixerVisible.value, pinned) { it.label(trackName) }
        val recap = if (base.recapNext) conversationRecap(base.feed) else null
        val prompt = listOfNotNull(recap, context, agentPrompt(start.text, start.reference)).joinToString("\n\n")
        sendPrompt(PromptIntent(prompt = prompt, displayText = start.text.ifBlank { SONG_REFERENCE_SHOWN }))
    }
}
