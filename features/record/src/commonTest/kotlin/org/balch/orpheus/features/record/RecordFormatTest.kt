package org.balch.orpheus.features.record

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordFormatTest {

    @Test
    fun elapsedShowsMinutesSecondsTenths() {
        assertEquals("00:00.0", formatElapsed(0f))
        assertEquals("00:12.4", formatElapsed(12.46f))
        assertEquals("02:05.0", formatElapsed(125f))
    }

    @Test
    fun takeMetaShowsDurationAndLocalDate() {
        val at = LocalDateTime(2026, 10, 17, 13, 24, 31).toInstant(TimeZone.UTC).toEpochMilliseconds()
        assertEquals("0:21 · 10-17 13:24", formatTakeMeta(21.4f, at, TimeZone.UTC))
        assertEquals("1:05 · 10-17 13:24", formatTakeMeta(65f, at, TimeZone.UTC))
    }

    @Test
    fun clockShowsMinutesAndPaddedSeconds() {
        assertEquals("0:00", formatClock(0f))
        assertEquals("0:09", formatClock(9.9f))
        assertEquals("2:05", formatClock(125f))
    }
}
