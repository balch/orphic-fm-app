package org.balch.orpheus.djapp.vibepresets

import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetTimeLabelTest {

    private val zone = TimeZone.UTC
    private val now = Instant.parse("2026-10-27T15:00:00Z").toEpochMilliseconds()

    private fun label(at: String) = presetTimeLabel(Instant.parse(at).toEpochMilliseconds(), now, zone)

    @Test
    fun underAMinuteIsJustNow() {
        assertEquals("just now", label("2026-10-27T14:59:30Z"))
    }

    @Test
    fun underAnHourIsMinutesAgo() {
        assertEquals("12 min ago", label("2026-10-27T14:48:00Z"))
    }

    @Test
    fun sameDayIsHoursAgo() {
        assertEquals("3h ago", label("2026-10-27T12:00:00Z"))
    }

    @Test
    fun previousCalendarDayIsYesterdayWithTime() {
        assertEquals("yesterday 9:40pm", label("2026-10-26T21:40:00Z"))
    }

    @Test
    fun olderIsMonthAndDay() {
        assertEquals("Oct 24", label("2026-10-24T08:00:00Z"))
    }
}
