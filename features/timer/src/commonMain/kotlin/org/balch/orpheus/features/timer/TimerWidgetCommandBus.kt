package org.balch.orpheus.features.timer

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.balch.orpheus.features.timer.TimerWidgetCommandBus.commands
import org.balch.orpheus.features.timer.TimerWidgetCommandBus.send

enum class TimerWidgetCommand { PLAY_PAUSE, STOP }

/**
 * Fire-and-forget command channel from the Android widget to the ViewModel.
 *
 * The BroadcastReceiver [emits][send] commands; the ViewModel [collects][commands]
 * them and dispatches to the appropriate action. Using a singleton rather than DI
 * because AppWidgetProvider has no access to the dependency graph.
 */
object TimerWidgetCommandBus {
    private val _commands = MutableSharedFlow<TimerWidgetCommand>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands: SharedFlow<TimerWidgetCommand> = _commands.asSharedFlow()

    fun send(command: TimerWidgetCommand) {
        _commands.tryEmit(command)
    }
}
