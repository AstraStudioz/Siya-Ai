package com.jarvis.assistant.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.jarvis.assistant.R

/**
 * 2x2 Minimalist widget with glowing orb and "Always with you ♡" companion tagline.
 */
class SiyaMinimalWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_siya_minimal)
            val pendingIntent = WidgetActions.createPendingIntent(
                context,
                WidgetActions.ACTION_OPEN,
                WidgetActions.RC_MINIMAL_OPEN + (appWidgetId % 500)
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
