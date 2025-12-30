package org.balch.orpheus.util

import com.diamondedge.logging.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
    DEBUG
}

data class LogEntry(
    val level: LogLevel,
    val message: String,
    val timestamp: Long = currentTimeMillis()
)

/**
 * Logger that outputs to console and UI debug panel. Uses inline lambdas to avoid string allocation
 * when logging is disabled.
 */
@SingleIn(AppScope::class)
class ConsoleLogger @Inject constructor() : Logger {

    @PublishedApi
    internal var enabled: Boolean = true
    @PublishedApi
    internal var debugEnabled: Boolean = true

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // For non-Compose access (backwards compat)
    val logs: List<LogEntry>
        get() = _logs.value

    @PublishedApi
    internal fun addToUi(message: String, level: LogLevel) {
        val newLog = LogEntry(level, message)
        val current = _logs.value.toMutableList()
        current.add(0, newLog)
        if (current.size > 100) current.removeLast()
        _logs.value = current
    }

    fun clear() {
        _logs.value = emptyList()
    }

    override fun verbose(tag: String, msg: String) {
    }

    override fun debug(tag: String, msg: String) {
        addToUi(msg, LogLevel.DEBUG)
    }

    override fun info(tag: String, msg: String) {
        addToUi(msg, LogLevel.INFO)
    }

    override fun warn(tag: String, msg: String, t: Throwable?) {
        addToUi(msg, LogLevel.WARNING)
    }

    override fun error(tag: String, msg: String, t: Throwable?) {
        addToUi("$msg\n\n${t?.message?:""}", LogLevel.ERROR)
    }

    override fun isLoggingVerbose(): Boolean  = false
    override fun isLoggingDebug(): Boolean = enabled && debugEnabled
    override fun isLoggingInfo(): Boolean  = enabled
    override fun isLoggingWarning(): Boolean = enabled
    override fun isLoggingError(): Boolean = enabled
}
