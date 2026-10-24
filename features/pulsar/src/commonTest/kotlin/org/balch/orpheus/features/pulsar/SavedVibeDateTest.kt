package org.balch.orpheus.features.pulsar

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.balch.orpheus.features.pulsar.playback.savedVibeDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedVibeDateTest {
    private val zone = TimeZone.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime(y, m, d, h, 0).toInstant(zone).toEpochMilliseconds()
    private val now = at(2026, 10, 24, 9)

    @Test fun sameDayIsToday() = assertEquals("today", savedVibeDate(at(2026, 10, 24, 1), now, zone))

    @Test fun theDayBeforeIsYesterday() = assertEquals("yesterday", savedVibeDate(at(2026, 10, 23, 23), now, zone))

    @Test fun olderShowsMonthAndDay() = assertEquals("Oct 21", savedVibeDate(at(2026, 10, 21), now, zone))

    @Test fun anotherYearAddsTheYear() = assertEquals("Dec 30 2025", savedVibeDate(at(2025, 12, 30), now, zone))
}
