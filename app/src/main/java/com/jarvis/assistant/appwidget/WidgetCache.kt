package com.jarvis.assistant.appwidget

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.WeatherData

/**
 * Manages persistent widget caching for weather data and assistant state.
 * Widgets read strictly from this cache to ensure instantaneous rendering without network waits.
 */
object WidgetCache {

    private const val PREFS_NAME = "widget_cache"

    private const val KEY_TEMP = "temp"
    private const val KEY_CONDITION = "condition"
    private const val KEY_CITY = "city"
    private const val KEY_ICON_RES = "icon_res_id"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_ASSISTANT_STATE = "assistant_state"

    const val MAX_CACHE_AGE_MS = 3 * 60 * 60 * 1000L // 3 hours
    
    fun isCacheStale(timestamp: Long, currentTime: Long = System.currentTimeMillis()): Boolean {
        return (currentTime - timestamp) > MAX_CACHE_AGE_MS || timestamp <= 0L
    }

    data class CachedWeather(
        val temperature: String,
        val condition: String,
        val cityName: String,
        val iconResId: Int,
        val timestamp: Long,
        val isPlaceholder: Boolean
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves fresh weather data into SharedPreferences.
     */
    fun saveWeather(context: Context, data: WeatherData) {
        getPrefs(context).edit()
            .putString(KEY_TEMP, data.temperature)
            .putString(KEY_CONDITION, data.condition)
            .putString(KEY_CITY, data.cityName)
            .putInt(KEY_ICON_RES, data.iconResId)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * Reads weather data. If older than 3 hours or location permission is missing,
     * returns an honest placeholder.
     */
    fun getWeather(context: Context): CachedWeather {
        val hasPermission = PermissionHelper.hasLocationPermission(context)
        val prefs = getPrefs(context)
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        val isStale = isCacheStale(timestamp, now)

        if (!hasPermission || isStale) {
            return CachedWeather(
                temperature = context.getString(R.string.widget_weather_placeholder_temp),
                condition = context.getString(R.string.widget_weather_placeholder_cond),
                cityName = context.getString(R.string.widget_weather_placeholder_city),
                iconResId = R.drawable.ic_weather_sun,
                timestamp = timestamp,
                isPlaceholder = true
            )
        }

        return CachedWeather(
            temperature = prefs.getString(KEY_TEMP, context.getString(R.string.widget_weather_placeholder_temp))
                ?: context.getString(R.string.widget_weather_placeholder_temp),
            condition = prefs.getString(KEY_CONDITION, context.getString(R.string.widget_weather_placeholder_cond))
                ?: context.getString(R.string.widget_weather_placeholder_cond),
            cityName = prefs.getString(KEY_CITY, context.getString(R.string.widget_weather_placeholder_city))
                ?: context.getString(R.string.widget_weather_placeholder_city),
            iconResId = prefs.getInt(KEY_ICON_RES, R.drawable.ic_weather_sun),
            timestamp = timestamp,
            isPlaceholder = false
        )
    }

    /**
     * Records assistant state (e.g. "active", "standby", "off").
     */
    fun saveAssistantState(context: Context, state: String) {
        getPrefs(context).edit()
            .putString(KEY_ASSISTANT_STATE, state)
            .apply()
    }

    /**
     * Determines whether the assistant service is actively online.
     */
    fun isAssistantOnline(context: Context): Boolean {
        if (JarvisConversationService.isSessionOn.value) return true
        val stored = getPrefs(context).getString(KEY_ASSISTANT_STATE, "off")
        return stored.equals("active", ignoreCase = true)
    }
}
