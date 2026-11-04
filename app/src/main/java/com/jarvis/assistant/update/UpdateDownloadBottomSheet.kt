package com.jarvis.assistant.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.R
import com.jarvis.assistant.data.update.DownloadState
import com.jarvis.assistant.data.update.UpdateInfo
import com.jarvis.assistant.databinding.BottomSheetUpdateDownloadBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Bottom sheet dialog providing two update paths:
 * 1. In-app direct GitHub download with resume, progress tracking, and SHA-256 verification.
 * 2. Telegram post deep link with browser fallback.
 */
class UpdateDownloadBottomSheet(
    private val updateInfo: UpdateInfo
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetUpdateDownloadBinding? = null
    private val binding get() = _binding!!

    private var downloadJob: Job? = null
    private var downloadedApkFile: File? = null
    private lateinit var downloader: UpdateDownloader

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetUpdateDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloader = UpdateDownloader(requireContext())

        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        binding.tvSheetTitle.text = "Install Siya AI v${updateInfo.versionName}"
        binding.tvSheetSubtitle.text = "Build ${updateInfo.versionCode} is ready for installation"

        if (updateInfo.telegramUrl.isBlank()) {
            binding.cardTelegramDownload.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        // GitHub Action Button
        binding.btnActionGithubDownload.setOnClickListener {
            if (downloadedApkFile != null && downloadedApkFile!!.exists()) {
                initiateInstall(downloadedApkFile!!)
            } else {
                startDownload()
            }
        }

        // Cancel Button
        binding.btnCancelDownload.setOnClickListener {
            cancelDownload()
        }

        // Telegram Button
        binding.btnOpenTelegram.setOnClickListener {
            openTelegramUrl(updateInfo.telegramUrl)
        }
    }

    private fun startDownload() {
        binding.btnCancelDownload.visibility = View.VISIBLE
        binding.btnActionGithubDownload.isEnabled = false
        binding.pbDownloadProgress.visibility = View.VISIBLE
        binding.pbDownloadProgress.isIndeterminate = false
        binding.tvDownloadStatus.text = "Connecting to server..."

        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            downloader.downloadApk(updateInfo).collectLatest { state ->
                handleDownloadState(state)
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        binding.btnCancelDownload.visibility = View.GONE
        binding.btnActionGithubDownload.isEnabled = true
        binding.btnActionGithubDownload.text = "Resume Download"
        binding.pbDownloadProgress.visibility = View.GONE
        binding.tvDownloadStatus.text = "Download paused. Tap Resume to continue."
    }

    private fun handleDownloadState(state: DownloadState) {
        when (state) {
            is DownloadState.Idle -> {
                binding.tvDownloadStatus.text = "Initializing download..."
            }
            is DownloadState.Downloading -> {
                binding.pbDownloadProgress.visibility = View.VISIBLE
                binding.pbDownloadProgress.progress = state.percent
                val downloadedMb = String.format("%.1f", state.bytesDownloaded / (1024.0 * 1024.0))
                val totalMb = if (state.totalBytes > 0) String.format("%.1f", state.totalBytes / (1024.0 * 1024.0)) else "?"
                val speedMb = String.format("%.1f", state.speedBytesPerSec / (1024.0 * 1024.0))

                binding.tvDownloadStatus.text = "Downloading: $downloadedMb MB / $totalMb MB (${state.percent}%) • $speedMb MB/s"
            }
            is DownloadState.VerifyingHash -> {
                binding.pbDownloadProgress.visibility = View.VISIBLE
                binding.pbDownloadProgress.isIndeterminate = true
                binding.tvDownloadStatus.text = "Verifying package integrity (SHA-256)..."
            }
            is DownloadState.ReadyToInstall -> {
                downloadedApkFile = state.apkFile
                binding.pbDownloadProgress.visibility = View.GONE
                binding.btnCancelDownload.visibility = View.GONE
                binding.btnActionGithubDownload.isEnabled = true
                binding.btnActionGithubDownload.text = "Install Now"
                binding.tvDownloadStatus.text = "Download complete and verified. Ready to install!"
                initiateInstall(state.apkFile)
            }
            is DownloadState.Failed -> {
                binding.pbDownloadProgress.visibility = View.GONE
                binding.btnCancelDownload.visibility = View.GONE
                binding.btnActionGithubDownload.isEnabled = true
                binding.btnActionGithubDownload.text = if (state.canRetry) "Retry Download" else "Download APK"
                binding.tvDownloadStatus.text = state.error
                Toast.makeText(requireContext(), state.error, Toast.LENGTH_LONG).show()
            }
            is DownloadState.Cancelled -> {
                binding.pbDownloadProgress.visibility = View.GONE
                binding.btnCancelDownload.visibility = View.GONE
                binding.btnActionGithubDownload.isEnabled = true
                binding.btnActionGithubDownload.text = "Resume Download"
                binding.tvDownloadStatus.text = "Download cancelled."
            }
        }
    }

    private fun initiateInstall(apkFile: File) {
        val activity = activity ?: return
        if (!AppInstaller.canRequestPackageInstalls(activity)) {
            Toast.makeText(activity, "Please allow Siya AI to install unknown apps, then tap Install Now.", Toast.LENGTH_LONG).show()
            AppInstaller.openManageUnknownAppsSettings(activity)
            return
        }

        val result = AppInstaller.installApk(activity, apkFile)
        result.onSuccess {
            dismissAllowingStateLoss()
        }.onFailure { err ->
            Toast.makeText(activity, "Installation failed: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openTelegramUrl(url: String) {
        if (url.isBlank()) return
        val uri = Uri.parse(url)

        try {
            // 1. Try launching official Telegram app
            val telegramIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("org.telegram.messenger")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(telegramIntent)
        } catch (_: Exception) {
            try {
                // 2. Try generic intent (e.g. Telegram X, Plus Messenger)
                val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(genericIntent)
            } catch (e: ActivityNotFoundException) {
                // 3. Fallback to web browser
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(browserIntent)
                } catch (e2: Exception) {
                    Toast.makeText(requireContext(), "Could not open link: ${e2.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloadJob?.cancel()
        _binding = null
    }
}
