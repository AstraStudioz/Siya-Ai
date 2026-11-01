package com.jarvis.assistant.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast

/**
 * Opens external community / promotional links safely.
 *
 * t.me links prefer the Telegram app when it is installed and fall back to any
 * browser-capable app. Missing apps and invalid resolutions are handled without crashing.
 */
object ExternalLinks {

    private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
    private const val DUPLICATE_TAP_WINDOW_MS = 800L

    private var lastOpenedAt = 0L

    /** Returns true if the link was handed to an app (or ignored as a duplicate tap). */
    fun open(context: Context, url: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenedAt < DUPLICATE_TAP_WINDOW_MS) return true
        lastOpenedAt = now

        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            null
        }
        if (uri == null) {
            Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
            return false
        }

        if (uri.host.equals("t.me", ignoreCase = true)) {
            val telegramIntent = Intent(Intent.ACTION_VIEW, uri).setPackage(TELEGRAM_PACKAGE)
            if (tryStart(context, telegramIntent)) return true
        }

        val browserIntent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (tryStart(context, browserIntent)) return true

        Toast.makeText(context, "No app available to open this link", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
