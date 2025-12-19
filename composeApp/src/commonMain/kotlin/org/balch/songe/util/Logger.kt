package org.balch.songe.util

import androidx.compose.runtime.mutableStateListOf

enum class LogLevel { INFO, WARNING, ERROR, DEBUG }

data class LogEntry(
    val level: LogLevel,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

object Logger {
    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> get() = _logs

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        _logs.add(0, LogEntry(level, message))
        if (_logs.size > 100) _logs.removeLast()
    }
    
    fun info(message: String) = log(message, LogLevel.INFO)
    fun warn(message: String) = log(message, LogLevel.WARNING)
    fun error(message: String) = log(message, LogLevel.ERROR)
    fun debug(message: String) = log(message, LogLevel.DEBUG)
    
    fun clear() {
        _logs.clear()
    }
}
