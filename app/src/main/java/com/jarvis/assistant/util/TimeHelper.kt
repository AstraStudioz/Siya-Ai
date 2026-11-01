package com.jarvis.assistant.util

import android.content.Context
import android.text.format.DateFormat
import org.json.JSONObject
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class CurrentTimeData(
    val iso: String,
    val timezoneId: String,
    val utcOffset: String,
    val dateText: String,
    val time12: String,
    val time24: String,
    val hour: Int,
    val minute: Int,
    val weekday: String,
    val periodHi: String,
    val tomorrowDate: String,
    val yesterdayDate: String,
    val deviceUses24h: Boolean
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("iso", iso)
            put("timezone_id", timezoneId)
            put("utc_offset", utcOffset)
            put("date_text", dateText)
            put("time12", time12)
            put("time24", time24)
            put("hour", hour)
            put("minute", minute)
            put("weekday", weekday)
            put("period_hi", periodHi)
            put("tomorrow_date", tomorrowDate)
            put("yesterday_date", yesterdayDate)
            put("device_uses_24h", deviceUses24h)
        }
    }
}

object TimeHelper {

    private val DATE_TEXT_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
    private val TIME_12_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val TIME_24_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun getCurrentTimeData(
        clock: Clock = Clock.systemDefaultZone(),
        context: Context? = null
    ): CurrentTimeData {
        val zdt = ZonedDateTime.now(clock)
        val hour = zdt.hour
        val periodHi = when (hour) {
            in 5..11 -> "subah"
            in 12..16 -> "dopahar"
            in 17..20 -> "shaam"
            else -> "raat"
        }

        val deviceUses24h = try {
            if (context != null) DateFormat.is24HourFormat(context) else false
        } catch (_: Exception) {
            false
        }

        return CurrentTimeData(
            iso = zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            timezoneId = zdt.zone.id,
            utcOffset = zdt.offset.id,
            dateText = zdt.format(DATE_TEXT_FORMATTER),
            time12 = zdt.format(TIME_12_FORMATTER),
            time24 = zdt.format(TIME_24_FORMATTER),
            hour = hour,
            minute = zdt.minute,
            weekday = zdt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            periodHi = periodHi,
            tomorrowDate = zdt.plusDays(1).format(DATE_TEXT_FORMATTER),
            yesterdayDate = zdt.minusDays(1).format(DATE_TEXT_FORMATTER),
            deviceUses24h = deviceUses24h
        )
    }

    fun getCurrentTimeJson(
        clock: Clock = Clock.systemDefaultZone(),
        context: Context? = null
    ): JSONObject {
        return getCurrentTimeData(clock, context).toJsonObject()
    }

    fun getFreshFormattedTime(clock: Clock = Clock.systemDefaultZone()): String {
        val zdt = ZonedDateTime.now(clock)
        val dateText = zdt.format(DATE_TEXT_FORMATTER)
        val time24 = zdt.format(TIME_24_FORMATTER)
        val time12 = zdt.format(TIME_12_FORMATTER)
        return "$dateText, $time12 ($time24) [${zdt.zone.id}, UTC${zdt.offset.id}]"
    }
}
