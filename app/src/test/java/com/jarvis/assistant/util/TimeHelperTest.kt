package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeHelperTest {

    private fun fixedClock(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, zone: ZoneId = ZoneId.of("Asia/Kolkata")): Clock {
        val zdt = ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone)
        return Clock.fixed(zdt.toInstant(), zone)
    }

    @Test
    fun testFormattingAndFields() {
        // Sunday, 25 October 2026, 14:43 in Asia/Kolkata (+05:30)
        val clock = fixedClock(2026, 10, 25, 14, 43, 0, ZoneId.of("Asia/Kolkata"))
        val data = TimeHelper.getCurrentTimeData(clock = clock)

        assertEquals("Sunday, 25 October 2026", data.dateText)
        assertEquals("2:43 PM", data.time12)
        assertEquals("14:43", data.time24)
        assertEquals(14, data.hour)
        assertEquals(43, data.minute)
        assertEquals("Sunday", data.weekday)
        assertEquals("Asia/Kolkata", data.timezoneId)
        assertEquals("+05:30", data.utcOffset)
        assertTrue(data.iso.startsWith("2026-10-25T14:43:00+05:30"))
        assertEquals("Monday, 26 October 2026", data.tomorrowDate)
        assertEquals("Saturday, 24 October 2026", data.yesterdayDate)
    }

    @Test
    fun testPeriodHiBoundaries() {
        // 05-11 subah
        assertEquals("subah", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 5, 0)).periodHi)
        assertEquals("subah", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 11, 59)).periodHi)

        // 12-16 dopahar
        assertEquals("dopahar", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 12, 0)).periodHi)
        assertEquals("dopahar", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 16, 59)).periodHi)

        // 17-20 shaam
        assertEquals("shaam", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 17, 0)).periodHi)
        assertEquals("shaam", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 20, 59)).periodHi)

        // else (21-23, 00-04) raat
        assertEquals("raat", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 21, 0)).periodHi)
        assertEquals("raat", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 23, 59)).periodHi)
        assertEquals("raat", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 0, 0)).periodHi)
        assertEquals("raat", TimeHelper.getCurrentTimeData(clock = fixedClock(2026, 10, 25, 4, 59)).periodHi)
    }

    @Test
    fun testTimezoneChange() {
        // At instant UTC 2026-10-25 12:00:00:
        val instant = Instant.parse("2026-10-25T12:00:00Z")

        val kolkataClock = Clock.fixed(instant, ZoneId.of("Asia/Kolkata"))
        val kolkataData = TimeHelper.getCurrentTimeData(clock = kolkataClock)
        assertEquals("Asia/Kolkata", kolkataData.timezoneId)
        assertEquals(17, kolkataData.hour) // 12 + 5:30 = 17:30
        assertEquals(30, kolkataData.minute)
        assertEquals("shaam", kolkataData.periodHi)

        val nyClock = Clock.fixed(instant, ZoneId.of("America/New_York"))
        val nyData = TimeHelper.getCurrentTimeData(clock = nyClock)
        assertEquals("America/New_York", nyData.timezoneId)
        assertEquals(8, nyData.hour) // 12 - 4 = 8:00 (EDT)
        assertEquals(0, nyData.minute)
        assertEquals("subah", nyData.periodHi)
    }

    @Test
    fun testMidnightRollover() {
        // Just before midnight: 23:59:59 on 25 October 2026
        val beforeMidnight = fixedClock(2026, 10, 25, 23, 59, 59)
        val dataBefore = TimeHelper.getCurrentTimeData(clock = beforeMidnight)
        assertEquals("Sunday, 25 October 2026", dataBefore.dateText)
        assertEquals("Monday, 26 October 2026", dataBefore.tomorrowDate)
        assertEquals("Saturday, 24 October 2026", dataBefore.yesterdayDate)

        // Right after midnight: 00:00:01 on 26 October 2026
        val afterMidnight = fixedClock(2026, 10, 26, 0, 0, 1)
        val dataAfter = TimeHelper.getCurrentTimeData(clock = afterMidnight)
        assertEquals("Monday, 26 October 2026", dataAfter.dateText)
        assertEquals("Tuesday, 27 October 2026", dataAfter.tomorrowDate)
        assertEquals("Sunday, 25 October 2026", dataAfter.yesterdayDate)
    }
}
