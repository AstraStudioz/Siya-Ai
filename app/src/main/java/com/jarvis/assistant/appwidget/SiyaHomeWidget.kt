package com.jarvis.assistant.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.jarvis.assistant.R

/**
 * 4x2 Primary Home widget featuring big clock, date, live weather row,
 * Siya AI brand branding, and character art on the right.
 */
class SiyaHomeWidget : AppWidgetProvider() {

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
            val views = RemoteViews(context.packageName, R.layout.widget_siya_home)

            // Weather data from cache
            val weather = WidgetCache.getWeather(context)
            views.setTextViewText(R.id.tv_weather_temp, weather.temperature)
            views.setTextViewText(R.id.tv_weather_cond, weather.condition)
            views.setImageViewResource(R.id.iv_weather_icon, weather.iconResId)

            // Tap anywhere to open the app
            val pendingIntent = WidgetActions.createPendingIntent(
                context,
                WidgetActions.ACTION_OPEN,
                WidgetActions.RC_HOME_OPEN + (appWidgetId % 500)
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
