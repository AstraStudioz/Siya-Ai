package com.jarvis.assistant.appwidget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.util.LocationWeatherHelper
import java.util.concurrent.TimeUnit

/**
 * Background worker that fetches current weather every 30 minutes,
 * updates SharedPreferences cache, and triggers widget refreshes.
 */
class WidgetUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val weather = LocationWeatherHelper.fetchWeather(applicationContext)
            if (weather != null) {
                WidgetCache.saveWeather(applicationContext, weather)
            }
            WidgetUpdater.updateAllWidgets(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in WidgetUpdateWorker: ${e.message}", e)
            WidgetUpdater.updateAllWidgets(applicationContext)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        const val UNIQUE_PERIODIC_WORK_NAME = "SiyaWidgetUpdateWork"

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
        }

        fun enqueueOneTime(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTime = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(oneTime)
        }
    }
}
