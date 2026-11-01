package org.balch.orpheus.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the audio host running while something on screen plays without the transport, such as the
 * cowbell on a paused vibe. Counted, so two holders release independently. Main thread only.
 */
@SingleIn(AppScope::class)
@Inject
class AudioHostHold {
    private var holders = 0
    private val _held = MutableStateFlow(false)
    val held: StateFlow<Boolean> = _held.asStateFlow()

    fun acquire() {
        if (holders++ == 0) _held.value = true
    }

    fun release() {
        if (holders == 0) return
        if (--holders == 0) _held.value = false
    }
}
