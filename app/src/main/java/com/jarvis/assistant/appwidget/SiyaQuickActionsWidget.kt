package com.jarvis.assistant.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.jarvis.assistant.R

/**
 * 4x1 Quick Actions widget with avatar, greeting, and 4 dedicated action buttons
 * (Voice, Chat, Tools, Settings).
 */
class SiyaQuickActionsWidget : AppWidgetProvider() {

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
            val views = RemoteViews(context.packageName, R.layout.widget_siya_quick_actions)
            val offset = (appWidgetId % 500) * 10

            // Open app on left avatar/greeting tap
            views.setOnClickPendingIntent(
                R.id.btn_left_open,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_OPEN,
                    WidgetActions.RC_QUICK_ACTIONS_OPEN + offset
                )
            )

            // Voice action
            views.setOnClickPendingIntent(
                R.id.btn_action_voice,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_VOICE,
                    WidgetActions.RC_QUICK_ACTIONS_VOICE + offset
                )
            )

            // Chat action
            views.setOnClickPendingIntent(
                R.id.btn_action_chat,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_CHAT,
                    WidgetActions.RC_QUICK_ACTIONS_CHAT + offset
                )
            )

            // Tools action (HandsFreeActivity)
            views.setOnClickPendingIntent(
                R.id.btn_action_tools,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_TOOLS,
                    WidgetActions.RC_QUICK_ACTIONS_TOOLS + offset
                )
            )

            // Settings action
            views.setOnClickPendingIntent(
                R.id.btn_action_settings,
                WidgetActions.createPendingIntent(
                    context,
                    WidgetActions.ACTION_SETTINGS,
                    WidgetActions.RC_QUICK_ACTIONS_SETTINGS + offset
                )
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
