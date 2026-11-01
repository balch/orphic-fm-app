package org.balch.orpheus.djapp.vibepresets

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** A short age for a preset: "just now", "12 min ago", "3h ago", "yesterday 9:40pm" or "Oct 24". */
fun presetTimeLabel(atMs: Long, nowMs: Long, zone: TimeZone): String {
    val age = nowMs - atMs
    val at = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    return when {
        age < MINUTE_MS -> "just now"
        age < HOUR_MS -> "${age / MINUTE_MS} min ago"
        age < DAY_MS && at.date == today -> "${age / HOUR_MS}h ago"
        at.date == today.minus(DatePeriod(days = 1)) -> "yesterday ${clock12(at.hour, at.minute)}"
        else -> "${MONTHS[at.month.ordinal]} ${at.day}"
    }
}

private fun clock12(hour: Int, minute: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "$h:${minute.toString().padStart(2, '0')}${if (hour < 12) "am" else "pm"}"
}
