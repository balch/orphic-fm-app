package org.balch.orpheus.djapp.ai.collab

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Something an agent tool needs the user to see or answer in the chat. */
sealed interface CollabEvent {
    /** A mix tool was refused because the Mixer is closed; the chat offers an Open Mixer button. */
    data class OpenMixerRequested(val reason: String) : CollabEvent

    /** An apply, load or reset would clear [changes] live edits; the chat asks the user to confirm. */
    data class WipeConfirmRequested(val changes: Int) : CollabEvent

    /** The agent left [control] alone because the user moved it this turn, to [userValue] as the mixer shows it. */
    data class AgentSkipped(val control: MixControl, val userValue: String) : CollabEvent
}

/** Tool-side events for the chat. Tools emit from any coroutine and never wait for a collector. */
@SingleIn(AppScope::class)
@Inject
class CollabEvents {
    private val _events = MutableSharedFlow<CollabEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CollabEvent> = _events.asSharedFlow()

    fun emit(e: CollabEvent) {
        _events.tryEmit(e)
    }
}
