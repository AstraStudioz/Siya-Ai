package com.jarvis.assistant.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.jarvis.assistant.R

/**
 * 4x1 System Info & Status widget showing live assistant readiness (Online or Off).
 */
class SiyaStatusWidget : AppWidgetProvider() {

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
            val views = RemoteViews(context.packageName, R.layout.widget_siya_status)

            val isOnline = WidgetCache.isAssistantOnline(context)
            if (isOnline) {
                views.setTextViewText(R.id.tv_status_state, context.getString(R.string.widget_status_online))
                views.setImageViewResource(R.id.iv_status_dot, R.drawable.bg_widget_status_dot_online)
            } else {
                views.setTextViewText(R.id.tv_status_state, context.getString(R.string.widget_status_off))
                views.setImageViewResource(R.id.iv_status_dot, R.drawable.bg_widget_status_dot_off)
            }

            val pendingIntent = WidgetActions.createPendingIntent(
                context,
                WidgetActions.ACTION_OPEN,
                WidgetActions.RC_STATUS_OPEN + (appWidgetId % 500)
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
