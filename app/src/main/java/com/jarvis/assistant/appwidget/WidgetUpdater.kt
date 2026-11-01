package com.jarvis.assistant.appwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock

/**
 * Dispatches updates to all 9 active Siya AI widgets on the home screen.
 */
object WidgetUpdater {

    private var lastStatusRefreshTime: Long = 0L
    private const val STATUS_THROTTLE_MS = 5000L // 5 seconds

    /**
     * Updates all home-screen widgets of all 9 types.
     */
    fun updateAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return

        // 1. Home Widget
        val homeIds = manager.getAppWidgetIds(ComponentName(context, SiyaHomeWidget::class.java))
        for (id in homeIds) SiyaHomeWidget.updateAppWidget(context, manager, id)

        // 2. Quick Access Widget
        val qaIds = manager.getAppWidgetIds(ComponentName(context, SiyaQuickAccessWidget::class.java))
        for (id in qaIds) SiyaQuickAccessWidget.updateAppWidget(context, manager, id)

        // 3. Quick Actions Widget
        val qactIds = manager.getAppWidgetIds(ComponentName(context, SiyaQuickActionsWidget::class.java))
        for (id in qactIds) SiyaQuickActionsWidget.updateAppWidget(context, manager, id)

        // 4. Voice Widget
        val voiceIds = manager.getAppWidgetIds(ComponentName(context, SiyaVoiceWidget::class.java))
        for (id in voiceIds) SiyaVoiceWidget.updateAppWidget(context, manager, id)

        // 5. Weather Widget
        val weatherIds = manager.getAppWidgetIds(ComponentName(context, SiyaWeatherWidget::class.java))
        for (id in weatherIds) SiyaWeatherWidget.updateAppWidget(context, manager, id)

        // 6. Minimal Widget
        val minIds = manager.getAppWidgetIds(ComponentName(context, SiyaMinimalWidget::class.java))
        for (id in minIds) SiyaMinimalWidget.updateAppWidget(context, manager, id)

        // 7. Shortcuts Widget
        val shortIds = manager.getAppWidgetIds(ComponentName(context, SiyaShortcutsWidget::class.java))
        for (id in shortIds) SiyaShortcutsWidget.updateAppWidget(context, manager, id)

        // 8. Quote Widget
        val quoteIds = manager.getAppWidgetIds(ComponentName(context, SiyaQuoteWidget::class.java))
        for (id in quoteIds) SiyaQuoteWidget.updateAppWidget(context, manager, id)

        // 9. Status Widget
        val statusIds = manager.getAppWidgetIds(ComponentName(context, SiyaStatusWidget::class.java))
        for (id in statusIds) SiyaStatusWidget.updateAppWidget(context, manager, id)
    }

    /**
     * Refreshes assistant status widgets (SiyaStatusWidget), throttled to at most once per 5 seconds.
     */
    fun refreshStatus(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusRefreshTime < STATUS_THROTTLE_MS) return
        lastStatusRefreshTime = now

        val manager = AppWidgetManager.getInstance(context) ?: return
        val statusIds = manager.getAppWidgetIds(ComponentName(context, SiyaStatusWidget::class.java))
        for (id in statusIds) {
            SiyaStatusWidget.updateAppWidget(context, manager, id)
        }
    }
}
