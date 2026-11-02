package com.jarvis.assistant.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles package installation operations, checking permissions,
 * requesting install from unknown sources settings if needed,
 * and opening the package installer via FileProvider.
 */
object AppInstaller {

    private const val TAG = "AppInstaller"

    /**
     * Checks if the app is authorized to request package installs on Android 8.0 (API 26) and above.
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Prompts the user to grant permission to install unknown apps.
     */
    fun openManageUnknownAppsSettings(activity: Activity, requestCode: Int = 1001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivityForResult(intent, requestCode)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open ACTION_MANAGE_UNKNOWN_APP_SOURCES: ${e.message}")
                try {
                    val genericIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    activity.startActivity(genericIntent)
                } catch (e2: Exception) {
                    Toast.makeText(activity, "Please allow unknown app installation in Android Settings.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Triggers the installation of the specified APK file using Android's PackageInstaller via FileProvider.
     *
     * @param context Application or Activity context.
     * @param apkFile The validated APK file.
     * @return Result.success if intent started, Result.failure with error message on exception.
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0) {
            return Result.failure(IllegalArgumentException("APK file does not exist or is empty."))
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity found to handle package install intent: ${e.message}")
            Result.failure(Exception("No package installer found on this device to install the APK."))
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException initiating package install: ${e.message}")
            Result.failure(Exception("Permission denied while opening package installer: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error launching APK installer: ${e.message}", e)
            Result.failure(Exception("Could not launch package installer: ${e.message}"))
        }
    }
}
