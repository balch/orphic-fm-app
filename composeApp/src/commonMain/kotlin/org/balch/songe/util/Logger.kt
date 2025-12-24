package org.balch.songe.util

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
object Logger {
    private const val TAG = "Songe"

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
        println("[$TAG] ${level.name}: $message")
        val newLog = LogEntry(level, message)
        val current = _logs.value.toMutableList()
        current.add(0, newLog)
        if (current.size > 100) current.removeLast()
        _logs.value = current
    }

    inline fun info(crossinline message: () -> String) {
        if (enabled) {
            addToUi(message(), LogLevel.INFO)
        }
    }

    inline fun warn(crossinline message: () -> String) {
        if (enabled) {
            addToUi(message(), LogLevel.WARNING)
        }
    }

    inline fun error(crossinline message: () -> String) {
        if (enabled) {
            addToUi(message(), LogLevel.ERROR)
        }
    }

    inline fun debug(crossinline message: () -> String) {
        if (enabled && debugEnabled) {
            addToUi(message(), LogLevel.DEBUG)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
