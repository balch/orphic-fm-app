package org.balch.orpheus.core.tts

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface SpeechEvent {
    data object Idle : SpeechEvent
    data class Speaking(val text: String, val wordIndex: Int, val totalWords: Int) : SpeechEvent
    data class Done(val fullText: String) : SpeechEvent
    data class Failed(val error: String) : SpeechEvent
}

@SingleIn(AppScope::class)
class SpeechEventBus @Inject constructor() {

    private val log = logging("SpeechEventBus")

    private val _events = MutableSharedFlow<SpeechEvent>(
        replay = 1,
        extraBufferCapacity = 5
    )

    val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    suspend fun emitSpeaking(text: String, wordIndex: Int, totalWords: Int) {
        log.debug { "Speaking: '$text' ($wordIndex/$totalWords)" }
        _events.emit(SpeechEvent.Speaking(text, wordIndex, totalWords))
    }

    suspend fun emitDone(fullText: String) {
        log.debug { "Done: '$fullText'" }
        _events.emit(SpeechEvent.Done(fullText))
    }

    suspend fun emitFailed(error: String) {
        log.warn { "Failed: $error" }
        _events.emit(SpeechEvent.Failed(error))
    }

    suspend fun emitIdle() {
        _events.emit(SpeechEvent.Idle)
    }
}
