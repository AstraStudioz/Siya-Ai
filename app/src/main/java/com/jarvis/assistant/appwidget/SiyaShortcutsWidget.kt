package com.jarvis.assistant.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.jarvis.assistant.R

/**
 * 2x2 Shortcuts widget presenting a four-quadrant grid of action buttons
 * (Voice, Chat, Tools, Settings).
 */
class SiyaShortcutsWidget : AppWidgetProvider() {

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
            val views = RemoteViews(context.packageName, R.layout.widget_siya_shortcuts)
            val offset = (appWidgetId % 500) * 10

            // Voice shortcut
            views.setOnClickPendingIntent(
                R.id.btn_shortcut_voice,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_VOICE,
                    WidgetActions.RC_SHORTCUTS_VOICE + offset
                )
            )

            // Chat shortcut
            views.setOnClickPendingIntent(
                R.id.btn_shortcut_chat,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_CHAT,
                    WidgetActions.RC_SHORTCUTS_CHAT + offset
                )
            )

            // Tools shortcut (HandsFreeActivity)
            views.setOnClickPendingIntent(
                R.id.btn_shortcut_tools,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_TOOLS,
                    WidgetActions.RC_SHORTCUTS_TOOLS + offset
                )
            )

            // Settings shortcut
            views.setOnClickPendingIntent(
                R.id.btn_shortcut_settings,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_SETTINGS,
                    WidgetActions.RC_SHORTCUTS_SETTINGS + offset
                )
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
