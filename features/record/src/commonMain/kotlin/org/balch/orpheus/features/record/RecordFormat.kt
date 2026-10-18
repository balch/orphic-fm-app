package org.balch.orpheus.features.record

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** `mm:ss.t` for the live take timer. */
internal fun formatElapsed(sec: Float): String {
    val tenths = (sec.coerceAtLeast(0f) * 10f).toInt()
    val minutes = tenths / 600
    val seconds = (tenths / 10) % 60
    return "${minutes.pad2()}:${seconds.pad2()}.${tenths % 10}"
}

/** `m:ss` for the listen bar. */
internal fun formatClock(sec: Float): String {
    val whole = sec.coerceAtLeast(0f).toInt()
    return "${whole / 60}:${(whole % 60).pad2()}"
}

/** `m:ss · MM-dd HH:mm` for a finished take row. */
internal fun formatTakeMeta(
    durationSec: Float,
    createdAtMs: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val whole = durationSec.coerceAtLeast(0f).toInt()
    val at = Instant.fromEpochMilliseconds(createdAtMs).toLocalDateTime(zone)
    val date = "${(at.month.ordinal + 1).pad2()}-${at.day.pad2()}"
    return "${whole / 60}:${(whole % 60).pad2()} · $date ${at.hour.pad2()}:${at.minute.pad2()}"
}

private fun Int.pad2() = toString().padStart(2, '0')
