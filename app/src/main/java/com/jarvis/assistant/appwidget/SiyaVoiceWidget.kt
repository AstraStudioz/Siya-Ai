package com.jarvis.assistant.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.jarvis.assistant.R

/**
 * 2x1 Voice Assistant widget featuring a coral glowing mic button and audio waveform.
 */
class SiyaVoiceWidget : AppWidgetProvider() {

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
            val views = RemoteViews(context.packageName, R.layout.widget_siya_voice)
            val pendingIntent = WidgetActions.createPendingIntent(
                context,
                WidgetActions.ACTION_VOICE,
                WidgetActions.RC_VOICE_TALK + (appWidgetId % 500)
            )

            // Both the dedicated coral mic button and root trigger the voice action
            views.setOnClickPendingIntent(R.id.btn_voice_mic, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
