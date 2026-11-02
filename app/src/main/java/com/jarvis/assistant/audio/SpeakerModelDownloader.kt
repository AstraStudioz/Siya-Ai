package com.jarvis.assistant.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

sealed class SpeakerDownloadState {
    object Idle : SpeakerDownloadState()
    data class Downloading(val progress: Int, val bytesRead: Long, val totalBytes: Long) : SpeakerDownloadState()
    object Extracting : SpeakerDownloadState()
    object Verifying : SpeakerDownloadState()
    object Success : SpeakerDownloadState()
    data class Error(val message: String) : SpeakerDownloadState()
}

object SpeakerModelDownloader {
    private const val TAG = "SpeakerDownloader"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    const val MIN_SIZE_BYTES = 10 * 1024 * 1024L      // 10 MB
    const val MAX_SIZE_BYTES = 20 * 1024 * 1024L      // 20 MB
    const val MIN_RAW_SIZE_BYTES = 5 * 1024 * 1024L   // 5 MB

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    private val _downloadState = MutableStateFlow<SpeakerDownloadState>(SpeakerDownloadState.Idle)
    val downloadState: StateFlow<SpeakerDownloadState> = _downloadState.asStateFlow()

    fun cancel() {
        Log.i(TAG, "Cancelling active speaker model download...")
        activeCall?.cancel()
        activeCall = null
        _downloadState.value = SpeakerDownloadState.Idle
    }

    suspend fun downloadAndInstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "vosk-spk-model")
        val cacheZip = File(context.cacheDir, "vosk-model-spk-0.4.zip")

        try {
            _downloadState.value = SpeakerDownloadState.Downloading(0, 0, 0)
            VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.DOWNLOADING)

            // Storage space check (needs at least ~60MB free for zip + extraction)
            val freeSpace = context.cacheDir.usableSpace
            if (freeSpace < 60 * 1024 * 1024L) {
                val err = "Insufficient storage space on device (need ~60MB free)."
                _downloadState.value = SpeakerDownloadState.Error(err)
                VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.MISSING)
                return@withContext false
            }

            if (cacheZip.exists()) {
                cacheZip.delete()
            }

            val request = Request.Builder()
                .url(MODEL_URL)
                .header("User-Agent", "SiyaAssistant/1.9")
                .build()

            val call = httpClient.newCall(request)
            activeCall = call
            val response = call.execute()

            if (!response.isSuccessful) {
                val err = "Download failed: Server returned HTTP ${response.code}."
                _downloadState.value = SpeakerDownloadState.Error(err)
                VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.MISSING)
                return@withContext false
            }

            val body = response.body ?: throw IOException("Empty response body from server")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(cacheZip).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var lastPercent = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            (downloadedBytes / (14 * 1024 * 1024L / 100)).toInt().coerceIn(0, 99)
                        }

                        if (percent != lastPercent) {
                            lastPercent = percent
                            _downloadState.value = SpeakerDownloadState.Downloading(percent, downloadedBytes, totalBytes)
                        }
                    }
                }
            }

            // Size sanity check (10MB - 20MB)
            val fileSize = cacheZip.length()
            if (fileSize < MIN_SIZE_BYTES || fileSize > MAX_SIZE_BYTES) {
                cacheZip.delete()
                val err = "Downloaded file size (${fileSize / (1024 * 1024)}MB) was unexpected. Corrupt download."
                _downloadState.value = SpeakerDownloadState.Error(err)
                VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.CORRUPT)
                return@withContext false
            }

            // Calculate and log SHA-256 non-blockingly
            logFileSha256(cacheZip)

            // Unzip and validate
            val success = extractAndValidate(cacheZip, targetDir, context)
            cacheZip.delete()

            if (success) {
                _downloadState.value = SpeakerDownloadState.Success
                true
            } else {
                _downloadState.value = SpeakerDownloadState.Error("Extraction failed or model files missing.")
                false
            }
        } catch (e: CancellationException) {
            cacheZip.delete()
            _downloadState.value = SpeakerDownloadState.Idle
            VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.MISSING)
            false
        } catch (e: UnknownHostException) {
            cacheZip.delete()
            val err = "No internet connection. Please check your network and retry."
            _downloadState.value = SpeakerDownloadState.Error(err)
            VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.MISSING)
            false
        } catch (e: Exception) {
            cacheZip.delete()
            Log.e(TAG, "Error downloading speaker model: ${e.message}", e)
            val err = if (e.message?.contains("canceled", ignoreCase = true) == true) {
                "Download cancelled."
            } else {
                "Download error: ${e.message ?: "Unknown error"}"
            }
            _downloadState.value = SpeakerDownloadState.Error(err)
            VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.MISSING)
            false
        } finally {
            activeCall = null
        }
    }

    suspend fun importFromFile(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "vosk-spk-model")
        val cacheZip = File(context.cacheDir, "imported-vosk-spk.zip")

        try {
            _downloadState.value = SpeakerDownloadState.Extracting
            VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.DOWNLOADING)

            if (cacheZip.exists()) cacheZip.delete()

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheZip).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Could not open selected file")

            logFileSha256(cacheZip)

            val success = extractAndValidate(cacheZip, targetDir, context)
            cacheZip.delete()

            if (success) {
                _downloadState.value = SpeakerDownloadState.Success
                true
            } else {
                val err = "Imported archive does not contain valid Vosk speaker model files."
                _downloadState.value = SpeakerDownloadState.Error(err)
                false
            }
        } catch (e: Exception) {
            cacheZip.delete()
            Log.e(TAG, "Error importing speaker model from file: ${e.message}", e)
            _downloadState.value = SpeakerDownloadState.Error("Import error: ${e.message ?: "Unknown error"}")
            VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.CORRUPT)
            false
        }
    }

    private fun extractAndValidate(zipFile: File, targetDir: File, context: Context): Boolean {
        _downloadState.value = SpeakerDownloadState.Extracting
        targetDir.mkdirs()

        // Unpack with zip-slip protection
        val extractedFiles = mutableListOf<File>()
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val destinationFile = File(targetDir, entryName)

                    // Zip-Slip security check:
                    val canonicalDest = destinationFile.canonicalPath
                    val canonicalTarget = targetDir.canonicalPath
                    if (!canonicalDest.startsWith(canonicalTarget)) {
                        throw SecurityException("Zip traversal vulnerability detected in: $entryName")
                    }

                    if (entry.isDirectory) {
                        destinationFile.mkdirs()
                    } else {
                        destinationFile.parentFile?.mkdirs()
                        FileOutputStream(destinationFile).use { out ->
                            zis.copyTo(out)
                        }
                        extractedFiles.add(destinationFile)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unzip error: ${e.message}", e)
            return false
        }

        // Handle nested top-level folder (e.g. vosk-model-spk-0.4/)
        flattenNestedModelFiles(targetDir)

        // Verifying
        _downloadState.value = SpeakerDownloadState.Verifying
        val rawFile = File(targetDir, "final.ext.raw")
        val mfccFile = File(targetDir, "mfcc.conf")
        val meanFile = File(targetDir, "mean.vec")
        val matFile = File(targetDir, "transform.mat")

        val allExist = rawFile.exists() && mfccFile.exists() && meanFile.exists() && matFile.exists()
        val rawValidSize = rawFile.length() > MIN_RAW_SIZE_BYTES

        if (!allExist || !rawValidSize) {
            Log.e(TAG, "Validation failed: rawExists=${rawFile.exists()} (${rawFile.length()} bytes), mfcc=${mfccFile.exists()}, mean=${meanFile.exists()}, mat=${matFile.exists()}")
            VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.CORRUPT)
            return false
        }

        // Reload Vosk model
        val reloaded = VoskModelManager.reloadSpeakerModel(context)
        Log.i(TAG, "Model extracted and verified. VoskModelManager reloaded = $reloaded")
        return reloaded
    }

    private fun flattenNestedModelFiles(targetDir: File) {
        val rootRaw = File(targetDir, "final.ext.raw")
        if (rootRaw.exists()) return

        // Search in subdirectories for final.ext.raw
        val subDirs = targetDir.listFiles { f -> f.isDirectory } ?: return
        for (sub in subDirs) {
            val nestedRaw = File(sub, "final.ext.raw")
            if (nestedRaw.exists()) {
                val files = sub.listFiles() ?: continue
                for (f in files) {
                    val dest = File(targetDir, f.name)
                    if (dest.exists()) dest.delete()
                    f.renameTo(dest)
                }
                sub.deleteRecursively()
                break
            }
        }
    }

    private fun logFileSha256(file: File) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hashBytes = digest.digest()
            val hex = hashBytes.joinToString("") { "%02x".format(it) }
            Log.i(TAG, "Downloaded file SHA-256: $hex (${file.name}, ${file.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not compute SHA-256: ${e.message}")
        }
    }
}
