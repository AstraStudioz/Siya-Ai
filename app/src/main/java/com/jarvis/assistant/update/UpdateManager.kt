package com.jarvis.assistant.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.update.UpdateCheckResult
import com.jarvis.assistant.data.update.UpdateInfo
import com.jarvis.assistant.ui.home.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Central update manager for Siya AI.
 * Handles update checking against GitHub Releases, local caching,
 * notification dispatch, and WorkManager background scheduling.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    const val PRIMARY_UPDATE_URL = "https://github.com/AstraStudioz/Siya-Ai/releases/latest/download/update.json"
    const val FALLBACK_API_URL = "https://api.github.com/repos/AstraStudioz/Siya-Ai/releases/latest"

    const val NOTIFICATION_CHANNEL_ID = "siya_updates"
    const val NOTIFICATION_ID = 2001
    const val EXTRA_OPEN_UPDATES_TAB = "extra_open_updates_tab"
    const val EXTRA_SHOW_UPDATE_POPUP = "extra_show_update_popup"

    private const val WORK_NAME_PERIODIC_UPDATE = "siya_periodic_update_check"

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _availableUpdateFlow = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdateFlow: StateFlow<UpdateInfo?> = _availableUpdateFlow.asStateFlow()

    init {
        // Hydrate from cached preference on process launch (guarded for unit test environments)
        runCatching {
            val cached = JarvisApp.instance.preferences.getCachedUpdateInfo()
            if (cached != null && cached.versionCode > BuildConfig.VERSION_CODE) {
                _availableUpdateFlow.value = cached
            }
        }
    }

    /**
     * Initializes notification channels and schedules periodic WorkManager update check.
     */
    fun initialize(context: Context) {
        createNotificationChannel(context)
        schedulePeriodicCheck(context)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "App Updates"
            val descriptionText = "Notifications when a new version of Siya AI is available"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                lightColor = 0xFFFF7B7B.toInt()
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Schedules periodic 6-hour WorkManager background update checks.
     */
    fun schedulePeriodicCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC_UPDATE,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
        Log.i(TAG, "Scheduled 6-hour periodic update check with WorkManager")
    }

    /**
     * Checks for updates asynchronously with primary URL and fallback GitHub API endpoint.
     */
    suspend fun checkForUpdate(isManual: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        val prefs = JarvisApp.instance.preferences

        // 1. Primary: Try direct update.json asset from latest release
        val primaryResult = fetchFromDirectJson(PRIMARY_UPDATE_URL)
        val finalInfo = if (primaryResult != null) {
            primaryResult
        } else {
            // 2. Fallback: Parse GitHub Releases API
            Log.w(TAG, "Direct update.json unavailable; trying GitHub Release API fallback")
            fetchFromGitHubApiFallback(FALLBACK_API_URL)
        }

        if (finalInfo == null) {
            return@withContext UpdateCheckResult.Error("Could not check for updates. Please check your internet connection.")
        }

        val currentVersionCode = BuildConfig.VERSION_CODE
        val isNewer = finalInfo.versionCode > currentVersionCode

        if (isNewer) {
            prefs.saveCachedUpdateInfo(finalInfo)
            _availableUpdateFlow.value = finalInfo

            // Trigger notification if allowed
            if (prefs.isUpdateNotificationsEnabled && !prefs.isVersionSkipped(finalInfo.versionCode)) {
                showUpdateNotification(JarvisApp.instance, finalInfo)
            }

            return@withContext UpdateCheckResult.UpdateAvailable(finalInfo)
        } else {
            // App is up to date, clear stale update from cache
            prefs.saveCachedUpdateInfo(null)
            _availableUpdateFlow.value = null
            return@withContext UpdateCheckResult.UpToDate
        }
    }

    private fun JsonObject.getNullableString(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        return try {
            if (element.isJsonPrimitive) element.asString else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromDirectJson(url: String): UpdateInfo? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Siya-AI-Android/${BuildConfig.VERSION_NAME}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Direct update.json returned HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val parsed = JsonParser.parseString(body)
                if (!parsed.isJsonObject) return null
                val obj = parsed.asJsonObject

                val versionCode = obj.get("versionCode")?.let {
                    if (it.isJsonPrimitive) {
                        try { it.asInt } catch (_: Exception) { null }
                    } else null
                } ?: return null

                val versionName = obj.getNullableString("versionName") ?: "1.0.$versionCode"
                val changelog = obj.getNullableString("changelog") ?: ""
                val apkUrl = obj.getNullableString("apkUrl") ?: return null
                val apkSha256 = obj.getNullableString("apkSha256") ?: ""
                val telegramUrl = obj.getNullableString("telegramUrl") ?: ""
                val mandatory = obj.get("mandatory")?.let {
                    if (it.isJsonPrimitive) {
                        try { it.asBoolean } catch (_: Exception) { false }
                    } else false
                } ?: false
                val publishedAt = obj.getNullableString("publishedAt") ?: ""

                UpdateInfo(
                    versionCode = versionCode,
                    versionName = versionName,
                    changelog = changelog,
                    apkUrl = apkUrl,
                    apkSha256 = apkSha256,
                    telegramUrl = telegramUrl,
                    mandatory = mandatory,
                    publishedAt = publishedAt
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct update.json request failed: ${e.message}")
            null
        }
    }

    private fun fetchFromGitHubApiFallback(url: String): UpdateInfo? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Siya-AI-Android/${BuildConfig.VERSION_NAME}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub Releases API returned HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                parseGitHubReleaseJson(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub Releases API fallback failed: ${e.message}")
            null
        }
    }

    /**
     * Parses raw GitHub Releases API JSON to synthesize UpdateInfo if update.json wasn't directly found.
     * Safely handles JsonNull and missing fields without throwing UnsupportedOperationException.
     */
    fun parseGitHubReleaseJson(jsonString: String): UpdateInfo? {
        return try {
            val parsed = JsonParser.parseString(jsonString)
            if (!parsed.isJsonObject) {
                Log.w(TAG, "GitHub release fallback JSON is not a JSON object")
                return null
            }
            val root = parsed.asJsonObject
            val tagName = root.getNullableString("tag_name") ?: "1.0.0"
            val bodyText = root.getNullableString("body")?.ifBlank { "New release available." } ?: "New release available."
            val publishedAt = root.getNullableString("published_at") ?: ""

            // Extract numeric version code from tag name (e.g., debug-apk-build-147-1 -> 147, or v1.0.147 -> 147)
            val tagVersionCode = Regex("""\b(\d+)\b""").findAll(tagName).lastOrNull()?.value?.toIntOrNull()

            val assetsElement = root.get("assets")
            val assets = if (assetsElement != null && assetsElement.isJsonArray) {
                assetsElement.asJsonArray
            } else {
                Log.w(TAG, "GitHub release has no valid assets array")
                return null
            }

            var apkUrl: String? = null
            var updateJsonUrl: String? = null
            var apkNameVersionCode: Int? = null

            for (i in 0 until assets.size()) {
                val item = assets.get(i)
                if (!item.isJsonObject) continue
                val asset = item.asJsonObject
                val name = asset.getNullableString("name") ?: ""
                val downloadUrl = asset.getNullableString("browser_download_url") ?: ""

                if (name.equals("update.json", ignoreCase = true)) {
                    updateJsonUrl = downloadUrl
                } else if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = downloadUrl
                    apkNameVersionCode = Regex("""\b(\d+)\b""").findAll(name).lastOrNull()?.value?.toIntOrNull()
                }
            }

            // If update.json asset exists in the release, fetch that directly for accurate metadata
            if (!updateJsonUrl.isNullOrBlank()) {
                val direct = fetchFromDirectJson(updateJsonUrl)
                if (direct != null) return direct
            }

            if (apkUrl.isNullOrBlank()) {
                Log.w(TAG, "No APK asset found in GitHub release")
                return null
            }

            val finalVersionCode = tagVersionCode ?: apkNameVersionCode ?: (BuildConfig.VERSION_CODE + 1)
            val cleanedVersionName = tagName.removePrefix("v").substringBefore("-").ifBlank { "1.0.$finalVersionCode" }

            UpdateInfo(
                versionCode = finalVersionCode,
                versionName = cleanedVersionName,
                changelog = bodyText,
                apkUrl = apkUrl,
                apkSha256 = "",
                telegramUrl = com.jarvis.assistant.util.BrandConfig.TELEGRAM_CHANNEL_URL,
                mandatory = false,
                publishedAt = publishedAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GitHub release fallback json: ${e.message}")
            null
        }
    }

    /**
     * Displays a system notification informing the user that a new version is available.
     */
    fun showUpdateNotification(context: Context, updateInfo: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted; skipping system notification")
                return
            }
        }

        val notifyIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATES_TAB, true)
            putExtra(EXTRA_SHOW_UPDATE_POPUP, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_bell)
            .setContentTitle("New Siya AI Update Available")
            .setContentText("Version ${updateInfo.versionName} is ready to install.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Version ${updateInfo.versionName} is available.\n\n${updateInfo.changelog.take(200)}")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}
