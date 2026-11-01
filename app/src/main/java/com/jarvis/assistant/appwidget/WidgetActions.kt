package com.jarvis.assistant.appwidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.ui.home.MainActivity

/**
 * Central helper for home-screen widget pending intents and navigation actions.
 */
object WidgetActions {
    const val EXTRA_WIDGET_ACTION = "widget_action"

    const val ACTION_OPEN = "open"
    const val ACTION_CHAT = "chat"
    const val ACTION_SETTINGS = "settings"
    const val ACTION_TOOLS = "tools"
    const val ACTION_VOICE = "voice"

    // Distinct request codes for every click target to prevent PendingIntent collision
    const val RC_QUICK_ACCESS_OPEN = 2001

    const val RC_QUICK_ACTIONS_OPEN = 2002
    const val RC_QUICK_ACTIONS_VOICE = 2003
    const val RC_QUICK_ACTIONS_CHAT = 2004
    const val RC_QUICK_ACTIONS_TOOLS = 2005
    const val RC_QUICK_ACTIONS_SETTINGS = 2006

    const val RC_VOICE_TALK = 2007

    const val RC_MINIMAL_OPEN = 2008

    const val RC_SHORTCUTS_VOICE = 2009
    const val RC_SHORTCUTS_CHAT = 2010
    const val RC_SHORTCUTS_TOOLS = 2011
    const val RC_SHORTCUTS_SETTINGS = 2012

    const val RC_HOME_OPEN = 2013
    const val RC_WEATHER_OPEN = 2014
    const val RC_QUOTE_OPEN = 2015
    const val RC_STATUS_OPEN = 2016

    /**
     * Builds an immutable PendingIntent targeting MainActivity with standard flags.
     */
    fun createPendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_WIDGET_ACTION, action)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
