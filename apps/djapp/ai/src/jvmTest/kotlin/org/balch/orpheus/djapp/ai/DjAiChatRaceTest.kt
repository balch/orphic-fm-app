package org.balch.orpheus.djapp.ai

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.features.ai.AgentActivityEvent
import org.balch.orpheus.features.ai.PromptIntent

class DjAiChatRaceTest {
    /** Runs [onTurnEnd] on the state step that takes a turn back to IDLE. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class TurnEndHook(
        private val real: MutableStateFlow<DjAiUiState>,
        private val onTurnEnd: () -> Unit,
    ) : MutableStateFlow<DjAiUiState> by real {
        override fun compareAndSet(expect: DjAiUiState, update: DjAiUiState): Boolean {
            val ok = real.compareAndSet(expect, update)
            if (ok && expect.phase != DjAiPhase.IDLE && update.phase == DjAiPhase.IDLE) onTurnEnd()
            return ok
        }
    }

    @Test
    fun aSendRightAfterAReplyKeepsItsTurn() {
        val collab = MixCollab()
        val sent = CopyOnWriteArrayList<PromptIntent>()
        lateinit var chat: DjAiChat
        var sender: Thread? = null
        // The user sends from another thread the moment the reply lands, before the ending turn's endTurn() runs.
        val state = TurnEndHook(MutableStateFlow(DjAiUiState())) {
            if (sender == null) sender = thread { chat.send("next", null) }.also { it.join(200) }
        }
        chat = DjAiChat(state, collab, CoroutineScope(Dispatchers.Unconfined), sendPrompt = { sent += it }, restartAgent = {})

        chat.send("first", null)
        chat.onActivity(AgentActivityEvent.Assistant("Done."))
        sender!!.join(5_000)

        assertEquals(listOf("first", "next"), sent.map { it.displayText })
        assertEquals(DjAiPhase.GENERATING, state.value.phase)
        assertTrue(collab.turnActive.value, "the new turn was ended by the old one's endTurn()")
    }
}
