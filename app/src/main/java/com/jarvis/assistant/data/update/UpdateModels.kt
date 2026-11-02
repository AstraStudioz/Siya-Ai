package com.jarvis.assistant.data.update

import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Data model representing an app update release metadata payload.
 * Served from update.json or GitHub Releases API.
 */
data class UpdateInfo(
    @SerializedName("versionCode")
    val versionCode: Int,

    @SerializedName("versionName")
    val versionName: String,

    @SerializedName("changelog")
    val changelog: String = "",

    @SerializedName("apkUrl")
    val apkUrl: String,

    @SerializedName("apkSha256")
    val apkSha256: String = "",

    @SerializedName("telegramUrl")
    val telegramUrl: String = "",

    @SerializedName("mandatory")
    val mandatory: Boolean = false,

    @SerializedName("publishedAt")
    val publishedAt: String = ""
)

/**
 * Sealed result type representing update checking outcome.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String, val throwable: Throwable? = null) : UpdateCheckResult()
}

/**
 * Sealed states representing the in-app APK download lifecycle.
 */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int,
        val speedBytesPerSec: Long = 0L
    ) : DownloadState()

    object VerifyingHash : DownloadState()
    data class ReadyToInstall(val apkFile: File) : DownloadState()
    data class Failed(val error: String, val canRetry: Boolean = true) : DownloadState()
    object Cancelled : DownloadState()
}
