package com.jarvis.assistant.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.jarvis.assistant.R

/**
 * 4x2 Weather + Assistant hub widget showing real temperature, condition,
 * city name, contextual greeting, and character illustration.
 */
class SiyaWeatherWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        WidgetUpdateWorker.enqueueOneTime(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateWorker.enqueuePeriodic(context)
        WidgetUpdateWorker.enqueueOneTime(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateWorker.cancelPeriodic(context)
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_siya_weather)

            // Live or cached weather
            val weather = WidgetCache.getWeather(context)
            views.setTextViewText(R.id.tv_weather_temp, weather.temperature)
            views.setTextViewText(R.id.tv_weather_cond, weather.condition)
            views.setTextViewText(R.id.tv_weather_city, weather.cityName)
            views.setImageViewResource(R.id.iv_weather_icon, weather.iconResId)

            // Dynamic time greeting
            val greeting = WidgetTimeHelper.getCurrentGreeting()
            views.setTextViewText(R.id.tv_greeting, greeting)

            // Click to open app
            val pendingIntent = WidgetActions.createPendingIntent(
                context,
                WidgetActions.ACTION_OPEN,
                WidgetActions.RC_WEATHER_OPEN + (appWidgetId % 500)
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
