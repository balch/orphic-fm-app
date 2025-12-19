package org.balch.songe.util

import androidx.compose.runtime.mutableStateListOf

enum class LogLevel { INFO, WARNING, ERROR, DEBUG }

data class LogEntry(
    val level: LogLevel,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Logger that outputs to console and UI debug panel.
 * Uses inline lambdas to avoid string allocation when logging is disabled.
 */
object Logger {
    private const val TAG = "Songe"
    
    @PublishedApi internal var enabled: Boolean = true
    @PublishedApi internal var debugEnabled: Boolean = true
    
    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> get() = _logs

    @PublishedApi
    internal fun addToUi(message: String, level: LogLevel) {
        println("[$TAG] ${level.name}: $message")
        _logs.add(0, LogEntry(level, message))
        if (_logs.size > 100) _logs.removeLast()
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
        _logs.clear()
    }
}
