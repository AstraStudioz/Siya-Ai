package com.jarvis.assistant.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.update.DownloadState
import com.jarvis.assistant.data.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Handles in-app APK downloading with resume support, progress streaming,
 * storage pre-checks, Wi-Fi constraints, and SHA-256 checksum verification.
 */
class UpdateDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "UpdateDownloader"
        private const val MIN_REQUIRED_STORAGE_BYTES = 50 * 1024 * 1024L // 50 MB minimum headroom
    }

    /**
     * Checks if current connection is unmetered (Wi-Fi/Ethernet).
     */
    fun isConnectedToWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Checks available storage space in the target directory in bytes.
     */
    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.cacheDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Calculates the SHA-256 hex digest of a local file.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Downloads an APK from [updateInfo.apkUrl] and emits [DownloadState].
     */
    fun downloadApk(updateInfo: UpdateInfo): Flow<DownloadState> = flow {
        emit(DownloadState.Idle)

        // 1. Wi-Fi check if preference enabled
        val prefs = JarvisApp.instance.preferences
        if (prefs.isUpdateWifiOnly && !isConnectedToWifi()) {
            emit(DownloadState.Failed("Download restricted to Wi-Fi. Connect to Wi-Fi or disable 'Wi-Fi only' in Settings.", canRetry = true))
            return@flow
        }

        // 2. Storage check
        val availableBytes = getAvailableStorageBytes()
        if (availableBytes < MIN_REQUIRED_STORAGE_BYTES) {
            val availMb = availableBytes / (1024 * 1024)
            emit(DownloadState.Failed("Insufficient storage space ($availMb MB available). At least 50 MB is required.", canRetry = false))
            return@flow
        }

        val targetDir = File(context.cacheDir, "apk_updates").apply { if (!exists()) mkdirs() }
        val apkFileName = "siya-ai-${updateInfo.versionCode}.apk"
        val targetFile = File(targetDir, apkFileName)
        val tempFile = File(targetDir, "$apkFileName.download")

        var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

        // If target file already exists and valid, verify hash directly
        if (targetFile.exists() && targetFile.length() > 0) {
            emit(DownloadState.VerifyingHash)
            val isValid = verifyChecksum(targetFile, updateInfo.apkSha256)
            if (isValid) {
                emit(DownloadState.ReadyToInstall(targetFile))
                return@flow
            } else {
                targetFile.delete()
                downloadedBytes = 0L
            }
        }

        val requestBuilder = Request.Builder().url(updateInfo.apkUrl)
        if (downloadedBytes > 0L) {
            requestBuilder.header("Range", "bytes=$downloadedBytes-")
        }

        val request = requestBuilder.build()
        var response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Network connection error: ${e.message}")
            emit(DownloadState.Failed("Connection error: ${e.message ?: "Network unreachable"}. Please retry.", canRetry = true))
            return@flow
        }

        // Handle Range response or full response fallback
        val isPartial = response.code == 206
        if (!response.isSuccessful && !isPartial) {
            // If Range was rejected (e.g. 416 Range Not Satisfiable), wipe temp file and try full download
            if (response.code == 416) {
                response.close()
                tempFile.delete()
                downloadedBytes = 0L
                val freshRequest = Request.Builder().url(updateInfo.apkUrl).build()
                response = try {
                    client.newCall(freshRequest).execute()
                } catch (e: Exception) {
                    emit(DownloadState.Failed("Download failed: ${e.message}", canRetry = true))
                    return@flow
                }
            } else {
                val code = response.code
                response.close()
                emit(DownloadState.Failed("Server returned error HTTP $code. Please retry.", canRetry = true))
                return@flow
            }
        }

        val body = response.body
        if (body == null) {
            response.close()
            emit(DownloadState.Failed("Empty response from update server.", canRetry = true))
            return@flow
        }

        val contentLength = body.contentLength()
        val totalBytes = if (isPartial) downloadedBytes + contentLength else contentLength

        var lastEmittedTime = System.currentTimeMillis()
        var lastEmittedBytes = downloadedBytes
        var speedBps = 0L

        try {
            val randomAccessFile = RandomAccessFile(tempFile, "rw")
            if (isPartial) {
                randomAccessFile.seek(downloadedBytes)
            } else {
                randomAccessFile.setLength(0L)
                downloadedBytes = 0L
            }

            body.byteStream().use { inputStream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    if (!currentCoroutineContext().isActive) {
                        randomAccessFile.close()
                        response.close()
                        emit(DownloadState.Cancelled)
                        return@flow
                    }

                    randomAccessFile.write(buffer, 0, read)
                    downloadedBytes += read

                    val now = System.currentTimeMillis()
                    if (now - lastEmittedTime >= 250L || (totalBytes > 0 && downloadedBytes == totalBytes)) {
                        val elapsedSec = (now - lastEmittedTime) / 1000.0
                        if (elapsedSec > 0) {
                            speedBps = ((downloadedBytes - lastEmittedBytes) / elapsedSec).toLong()
                        }
                        lastEmittedTime = now
                        lastEmittedBytes = downloadedBytes

                        val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                        emit(DownloadState.Downloading(downloadedBytes, totalBytes, percent, speedBps))
                    }
                }
            }
            randomAccessFile.close()
            response.close()

            // 3. Move temp file to target file
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            // 4. Verify SHA-256
            emit(DownloadState.VerifyingHash)
            val isValid = verifyChecksum(targetFile, updateInfo.apkSha256)
            if (isValid) {
                Log.i(TAG, "SHA-256 verification passed for ${targetFile.name}")
                emit(DownloadState.ReadyToInstall(targetFile))
            } else {
                Log.e(TAG, "SHA-256 verification failed! Deleting corrupted file.")
                targetFile.delete()
                emit(DownloadState.Failed("Security check failed: APK SHA-256 mismatch. The download was corrupted. Please retry.", canRetry = true))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download loop failed: ${e.message}", e)
            emit(DownloadState.Failed("Download interrupted: ${e.message ?: "Unknown error"}. You can resume anytime.", canRetry = true))
        }
    }.flowOn(Dispatchers.IO)

    private fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) {
            // If server didn't specify sha256, accept file if size is non-zero
            return file.length() > 0
        }
        val computed = computeSha256(file)
        return computed.equals(expectedSha256.trim(), ignoreCase = true)
    }
}
