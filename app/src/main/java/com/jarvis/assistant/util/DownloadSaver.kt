package com.jarvis.assistant.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Universal download saver for code ZIP archives and project exports across Android versions.
 * - Android 10+: Scoped Storage MediaStore.Downloads ("Download/Siya Ai")
 * - Android 8-9: Public Downloads with WRITE_EXTERNAL_STORAGE, with seamless FileProvider fallback.
 */
object DownloadSaver {

    sealed class Result {
        data class Success(
            val uri: Uri,
            val displayPath: String,
            val fileName: String,
            val isShareFallback: Boolean = false
        ) : Result()

        data class Error(
            val userMessage: String,
            val cause: Throwable? = null
        ) : Result()
    }

    /**
     * Saves raw ZIP bytes to the device Downloads/Siya Ai folder on a background dispatcher.
     */
    suspend fun saveZip(
        context: Context,
        zipBytes: ByteArray,
        fileName: String
    ): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(appContext, zipBytes, fileName)
            } else {
                saveViaLegacyStorage(appContext, zipBytes, fileName)
            }
        } catch (e: Exception) {
            val isDiskFull = e is IOException && (e.message?.contains("ENOSPC", ignoreCase = true) == true ||
                    e.message?.contains("space", ignoreCase = true) == true)
            val message = if (isDiskFull) {
                "Storage full: cannot save $fileName. Please free up space."
            } else {
                "Failed to save $fileName: ${e.localizedMessage ?: "Unknown error"}"
            }
            Result.Error(message, e)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        zipBytes: ByteArray,
        fileName: String
    ): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Siya Ai")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result.Error("Could not create entry in Downloads.")

        try {
            resolver.openOutputStream(uri)?.use { os ->
                os.write(zipBytes)
                os.flush()
            } ?: run {
                resolver.delete(uri, null, null)
                return Result.Error("Failed to open output stream for download.")
            }

            // Mark as complete (no longer pending)
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return Result.Success(
                uri = uri,
                displayPath = "Downloads/Siya Ai/$fileName",
                fileName = fileName,
                isShareFallback = false
            )
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {}
            throw e
        }
    }

    private fun saveViaLegacyStorage(
        context: Context,
        zipBytes: ByteArray,
        fileName: String
    ): Result {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val authority = "${context.packageName}.fileprovider"

        if (hasPermission) {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val siyaDir = File(publicDownloads, "Siya Ai")
            if (!siyaDir.exists() && !siyaDir.mkdirs()) {
                // If directory creation failed, fallback to cache
                return saveToCacheFallback(context, authority, zipBytes, fileName)
            }

            val targetFile = File(siyaDir, fileName)
            FileOutputStream(targetFile).use { fos ->
                fos.write(zipBytes)
                fos.flush()
            }

            val contentUri = FileProvider.getUriForFile(context, authority, targetFile)
            return Result.Success(
                uri = contentUri,
                displayPath = "Downloads/Siya Ai/$fileName",
                fileName = fileName,
                isShareFallback = false
            )
        } else {
            // Permission not granted on Android 8-9: fallback to cache and offer FileProvider share
            return saveToCacheFallback(context, authority, zipBytes, fileName)
        }
    }

    private fun saveToCacheFallback(
        context: Context,
        authority: String,
        zipBytes: ByteArray,
        fileName: String
    ): Result {
        val zipsDir = File(context.cacheDir, "zips")
        if (!zipsDir.exists()) {
            zipsDir.mkdirs()
        }

        val cacheFile = File(zipsDir, fileName)
        FileOutputStream(cacheFile).use { fos ->
            fos.write(zipBytes)
            fos.flush()
        }

        val contentUri = FileProvider.getUriForFile(context, authority, cacheFile)
        return Result.Success(
            uri = contentUri,
            displayPath = "Downloads/Siya Ai/$fileName",
            fileName = fileName,
            isShareFallback = true
        )
    }

    /**
     * Builds an ACTION_VIEW Intent for opening the saved ZIP with an archive viewer.
     */
    fun createOpenIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Builds an ACTION_SEND Intent for sharing the saved ZIP.
     */
    fun createShareIntent(uri: Uri, fileName: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
