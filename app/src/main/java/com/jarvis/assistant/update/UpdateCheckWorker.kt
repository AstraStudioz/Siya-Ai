package com.jarvis.assistant.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.update.UpdateCheckResult

/**
 * Background WorkManager CoroutineWorker scheduled to check for updates periodically.
 * Executes silently in the background, updating cached state and alerting via notification if enabled.
 */
class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "UpdateCheckWorker"
    }

    override suspend fun doWork(): Result {
        val prefs = JarvisApp.instance.preferences
        if (!prefs.isUpdateAutoCheckEnabled) {
            Log.d(TAG, "Auto update check disabled by user; skipping worker execution.")
            return Result.success()
        }

        return try {
            when (val checkResult = UpdateManager.checkForUpdate(isManual = false)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    Log.i(TAG, "Background update check found new version: ${checkResult.updateInfo.versionName} (${checkResult.updateInfo.versionCode})")
                    Result.success()
                }
                is UpdateCheckResult.UpToDate -> {
                    Log.d(TAG, "Background update check completed: app is up to date.")
                    Result.success()
                }
                is UpdateCheckResult.Error -> {
                    Log.w(TAG, "Background update check failed: ${checkResult.message}; will retry on next cycle.")
                    // Return retry so WorkManager retries with backoff when online
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during periodic update check: ${e.message}")
            Result.retry()
        }
    }
}
