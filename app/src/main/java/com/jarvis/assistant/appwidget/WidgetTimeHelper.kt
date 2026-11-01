package com.jarvis.assistant.appwidget

import java.util.Calendar

/**
 * Provides time-of-day greetings and helper logic for widgets.
 */
object WidgetTimeHelper {

    /**
     * Returns a time-based greeting based on a 24-hour hour integer (0..23).
     * 05-11 -> Good Morning!
     * 12-16 -> Good Afternoon!
     * 17-20 -> Good Evening!
     * otherwise -> Good Night!
     */
    fun getGreeting(hour: Int): String {
        return when (hour) {
            in 5..11 -> "Good Morning!"
            in 12..16 -> "Good Afternoon!"
            in 17..20 -> "Good Evening!"
            else -> "Good Night!"
        }
    }

    /**
     * Gets the current hour greeting based on system calendar.
     */
    fun getCurrentGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return getGreeting(hour)
    }
}
