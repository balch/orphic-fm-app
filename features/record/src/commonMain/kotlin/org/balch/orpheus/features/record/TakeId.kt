package org.balch.orpheus.features.record

import kotlinx.datetime.LocalDateTime

/** Builds the take folder name, e.g. `take-2026-10-17-132431`, from local time. */
fun takeIdFor(time: LocalDateTime): String {
    fun two(n: Int) = n.toString().padStart(2, '0')
    val date = "${time.year.toString().padStart(4, '0')}-${two(time.month.ordinal + 1)}-${two(time.day)}"
    return "take-$date-${two(time.hour)}${two(time.minute)}${two(time.second)}"
}
