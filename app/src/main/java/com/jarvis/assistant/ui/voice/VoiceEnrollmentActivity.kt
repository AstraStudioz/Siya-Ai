package com.jarvis.assistant.ui.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.AudioUtils
import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.audio.SpeakerModelStatus
import com.jarvis.assistant.audio.VoiceMatchStatus
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.data.voice.VoiceSecurityStorage
import com.jarvis.assistant.databinding.ActivityVoiceEnrollmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

class VoiceEnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceEnrollmentBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    // 5 natural speech prompts mixing Hindi/Hinglish, English, and numbers
    private val samplePrompts = listOf(
        "\"Hey Siya, wake up and check my notifications\"",
        "\"Siya, mera aaj ka schedule aur calendar batao\"",
        "\"Call emergency contact number 9 8 7 6 5 4 3 2 1 0\"",
        "\"Mujhe kal subah saat baje ka alarm laga do\"",
        "\"Set volume to 80 percent and play music\""
    )

    private val recordedVectors = mutableListOf<FloatArray>()
    private var currentSampleIndex = 0
    private var isRecording = false
    private var isTesting = false
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val recordingBuffer = ByteArrayOutputStream()

    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Microphone permission required for voice enrollment", Toast.LENGTH_LONG).show()
        }
    }

    private val importModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            lifecycleScope.launch {
                com.jarvis.assistant.audio.SpeakerModelDownloader.importFromFile(applicationContext, selectedUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvUnifiedFooter.setOnClickListener {
            com.jarvis.assistant.util.ExternalLinks.open(this, com.jarvis.assistant.util.BrandConfig.TELEGRAM_CHANNEL_URL)
        }

        // Initialize Vosk models if not ready
        VoskModelManager.init(applicationContext, lifecycleScope)

        setupUI()
        setupListeners()
        setupDownloaderUI()
        observeModelStatus()
        observeDownloaderState()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupDownloaderUI() {
        binding.btnDownloadModel.setOnClickListener {
            lifecycleScope.launch {
                com.jarvis.assistant.audio.SpeakerModelDownloader.downloadAndInstall(applicationContext)
            }
        }

        binding.btnImportModel.setOnClickListener {
            importModelLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }

        binding.btnCancelDownload.setOnClickListener {
            com.jarvis.assistant.audio.SpeakerModelDownloader.cancel()
        }

        binding.btnRetryDownload.setOnClickListener {
            lifecycleScope.launch {
                com.jarvis.assistant.audio.SpeakerModelDownloader.downloadAndInstall(applicationContext)
            }
        }
    }

    private fun observeDownloaderState() {
        lifecycleScope.launch {
            com.jarvis.assistant.audio.SpeakerModelDownloader.downloadState.collect { state ->
                when (state) {
                    is com.jarvis.assistant.audio.SpeakerDownloadState.Idle -> {
                        binding.layoutDownloadProgress.visibility = View.GONE
                        binding.layoutModelActionButtons.visibility = View.VISIBLE
                        binding.btnRetryDownload.visibility = View.GONE
                    }
                    is com.jarvis.assistant.audio.SpeakerDownloadState.Downloading -> {
                        binding.layoutDownloadProgress.visibility = View.VISIBLE
                        binding.layoutModelActionButtons.visibility = View.GONE
                        binding.btnRetryDownload.visibility = View.GONE
                        binding.progressModelDownload.progress = state.progress
                        val mbRead = "%.1f".format(state.bytesRead / (1024f * 1024f))
                        val mbTotal = if (state.totalBytes > 0) "%.1f MB".format(state.totalBytes / (1024f * 1024f)) else "14 MB"
                        binding.tvDownloadStatus.text = "Downloading: ${state.progress}% ($mbRead / $mbTotal)"
                    }
                    is com.jarvis.assistant.audio.SpeakerDownloadState.Extracting -> {
                        binding.layoutDownloadProgress.visibility = View.VISIBLE
                        binding.layoutModelActionButtons.visibility = View.GONE
                        binding.btnRetryDownload.visibility = View.GONE
                        binding.tvDownloadStatus.text = "Extracting & verifying model files..."
                    }
                    is com.jarvis.assistant.audio.SpeakerDownloadState.Verifying -> {
                        binding.layoutDownloadProgress.visibility = View.VISIBLE
                        binding.layoutModelActionButtons.visibility = View.GONE
                        binding.btnRetryDownload.visibility = View.GONE
                        binding.tvDownloadStatus.text = "Validating x-vector neural network..."
                    }
                    is com.jarvis.assistant.audio.SpeakerDownloadState.Success -> {
                        binding.cardModelStatusWarning.visibility = View.GONE
                        Toast.makeText(this@VoiceEnrollmentActivity, "Speaker recognition model installed and ready!", Toast.LENGTH_SHORT).show()
                    }
                    is com.jarvis.assistant.audio.SpeakerDownloadState.Error -> {
                        binding.layoutDownloadProgress.visibility = View.VISIBLE
                        binding.layoutModelActionButtons.visibility = View.VISIBLE
                        binding.btnRetryDownload.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text = state.message
                        Toast.makeText(this@VoiceEnrollmentActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun observeModelStatus() {
        lifecycleScope.launch {
            VoskModelManager.speakerModelStatus.collect { status ->
                val isReady = (status == SpeakerModelStatus.READY)
                binding.cardModelStatusWarning.visibility = if (isReady) View.GONE else View.VISIBLE
                if (!isReady) {
                    binding.tvModelWarningTitle.text = when (status) {
                        SpeakerModelStatus.DOWNLOADING -> "DOWNLOADING SPEAKER MODEL"
                        SpeakerModelStatus.CORRUPT -> "SPEAKER MODEL CORRUPT"
                        else -> "Speaker model not installed (14 MB)"
                    }
                    binding.tvModelWarningDesc.text = when (status) {
                        SpeakerModelStatus.MISSING -> "Voice biometrics requires the open-source Vosk speaker recognition neural net to extract x-vectors and recognize your unique voice."
                        SpeakerModelStatus.CORRUPT -> "Speaker model files are incomplete or corrupt. Please redownload or re-import the model."
                        SpeakerModelStatus.DOWNLOADING -> "Downloading open-source Vosk speaker model from alphacephei.com..."
                        else -> "Speaker model is initializing. Voice enrollment will become available once loaded."
                    }
                    if (currentSampleIndex < samplePrompts.size) {
                        binding.btnRecordSample.isEnabled = false
                        binding.btnRecordSample.text = when (status) {
                            SpeakerModelStatus.DOWNLOADING -> "SPEAKER MODEL DOWNLOADING..."
                            SpeakerModelStatus.CORRUPT -> "SPEAKER MODEL CORRUPT"
                            else -> "SPEAKER MODEL UNAVAILABLE"
                        }
                    }
                } else {
                    if (!isRecording && currentSampleIndex < samplePrompts.size) {
                        binding.btnRecordSample.isEnabled = true
                        binding.btnRecordSample.text = "TAP & SPEAK PHRASE"
                    }
                }
            }
        }
    }

    private fun setupUI() {
        val hasProfile = preferences.hasOwnerVoiceProfile()
        val isEnabled = preferences.isOwnerVoiceEnabled && hasProfile

        binding.switchOwnerOnly.isChecked = isEnabled
        binding.switchOwnerOnly.isEnabled = hasProfile

        if (hasProfile) {
            binding.tvEnrollmentStatus.text = if (isEnabled) "OWNER VOICE: ACTIVE" else "OWNER VOICE: OFF"
            binding.tvEnrollmentStatus.setTextColor(getColor(if (isEnabled) R.color.primary_cyan else R.color.text_secondary))
            binding.tvStatusDescription.text = "Calibrated to your voice. Convenience lock filters background voices and reduces stranger speech pickup."
            binding.viewStatusDot.setBackgroundResource(if (isEnabled) R.drawable.circle_cyan else R.drawable.bg_jarvis_chip)
        } else {
            binding.tvEnrollmentStatus.text = "OWNER VOICE: NOT ENROLLED"
            binding.tvEnrollmentStatus.setTextColor(getColor(R.color.status_amber))
            binding.tvStatusDescription.text = "Enroll 5 voice samples below to calibrate your biometric profile. Convenience lock stays OFF until enrolled."
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_jarvis_chip)
        }

        // Sensitivity
        val baseT = preferences.voiceStorage.calibratedThreshold
        val currentThreshold = preferences.ownerVoiceThreshold
        when {
            currentThreshold <= baseT - 0.03f -> binding.rbStrict.isChecked = true
            currentThreshold >= baseT + 0.03f -> binding.rbRelaxed.isChecked = true
            else -> binding.rbBalanced.isChecked = true
        }

        // Adaptive switch
        binding.switchAdaptiveProfile.isChecked = preferences.voiceStorage.isAdaptationEnabled

        updateEnrollmentStepUI()
        checkFrequentRejections()
    }

    private fun updateEnrollmentStepUI() {
        val total = samplePrompts.size
        val accentColor = ContextCompat.getColor(this, R.color.primary_cyan)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        val dots = listOf(
            binding.tvStep1Dot,
            binding.tvStep2Dot,
            binding.tvStep3Dot,
            binding.tvStep4Dot,
            binding.tvStep5Dot
        )

        dots.forEachIndexed { index, tv ->
            if (index < currentSampleIndex) {
                tv.setTextColor(accentColor)
                tv.text = "✓ Sample ${index + 1}"
            } else if (index == currentSampleIndex) {
                tv.setTextColor(accentColor)
                tv.text = "● Sample ${index + 1}"
            } else {
                tv.setTextColor(mutedColor)
                tv.text = "○ Sample ${index + 1}"
            }
        }

        if (currentSampleIndex < total) {
            binding.tvStepIndicator.text = "TRAIN STEP ${currentSampleIndex + 1} OF $total"
            binding.tvPromptPhrase.text = samplePrompts[currentSampleIndex]
            binding.btnRecordSample.text = "TAP & SPEAK PHRASE"
            binding.tvRecordFeedback.text = "Tap to start recording sample ${currentSampleIndex + 1}"
        } else {
            binding.tvStepIndicator.text = "ENROLLMENT COMPLETE (5 SAMPLES)"
            binding.tvPromptPhrase.text = "All 5 biometric samples registered!"
            binding.btnRecordSample.text = "RE-RECORD SAMPLES"
            binding.tvRecordFeedback.text = "Owner voice profile is encrypted in Android Keystore."
        }
    }

    private fun setupListeners() {
        binding.switchOwnerOnly.setOnCheckedChangeListener { _, isChecked ->
            preferences.isOwnerVoiceEnabled = isChecked
            setupUI()
            val msg = if (isChecked) {
                "Owner Voice Lock ENABLED"
            } else {
                "Owner Voice Lock DISABLED (Accepts all speech)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.rgSensitivity.setOnCheckedChangeListener { _, checkedId ->
            val baseT = preferences.voiceStorage.calibratedThreshold
            val newThreshold = when (checkedId) {
                R.id.rbStrict -> baseT + OwnerVoiceManager.STRICT_DELTA
                R.id.rbRelaxed -> baseT + OwnerVoiceManager.RELAXED_DELTA
                else -> baseT
            }
            preferences.ownerVoiceThreshold = newThreshold

            val label = when (checkedId) {
                R.id.rbStrict -> "Strict (%.2f)".format(newThreshold)
                R.id.rbRelaxed -> "Relaxed (%.2f)".format(newThreshold)
                else -> "Balanced (%.2f)".format(newThreshold)
            }
            Toast.makeText(this, "Biometric threshold set to $label", Toast.LENGTH_SHORT).show()
        }

        binding.switchAdaptiveProfile.setOnCheckedChangeListener { _, isChecked ->
            preferences.voiceStorage.isAdaptationEnabled = isChecked
            val msg = if (isChecked) "Automatic voice adaptation enabled" else "Automatic voice adaptation disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnResetAdaptation.setOnClickListener {
            preferences.voiceStorage.resetAdaptation()
            Toast.makeText(this, "Adaptive profile reset to original enrolled samples.", Toast.LENGTH_SHORT).show()
        }

        binding.btnRecordSample.setOnClickListener {
            if (VoskModelManager.speakerModelStatus.value != SpeakerModelStatus.READY) {
                Toast.makeText(this, "Speaker model is not ready.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (currentSampleIndex >= samplePrompts.size) {
                recordedVectors.clear()
                currentSampleIndex = 0
                binding.tvVoiceProfileQuality.visibility = View.GONE
                updateEnrollmentStepUI()
                return@setOnClickListener
            }

            // Explicit biometric consent check before first sample
            if (!preferences.voiceStorage.hasGivenConsent) {
                showBiometricConsentDialog {
                    startRecordingSample()
                }
                return@setOnClickListener
            }

            if (!isRecording) {
                startRecordingSample()
            } else {
                stopRecordingSample()
            }
        }

        binding.btnTestVoice.setOnClickListener {
            if (!isTesting) {
                startTestingVoice()
            } else {
                stopTestingVoice()
            }
        }

        binding.btnClearTestHistory.setOnClickListener {
            preferences.voiceStorage.clearTestHistory()
            binding.tvSuggestionRelaxed.visibility = View.GONE
            Toast.makeText(this, "Test history cleared.", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearProfile.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete Voice Profile?")
                .setMessage("This permanently wipes your encrypted voice vectors, adaptive samples, test history, and consent record. Siya will accept all voices.")
                .setPositiveButton("Delete Permanently") { _, _ ->
                    preferences.clearOwnerVoiceProfile()
                    recordedVectors.clear()
                    currentSampleIndex = 0
                    binding.tvVoiceProfileQuality.visibility = View.GONE
                    setupUI()
                    Toast.makeText(this, "Voice profile permanently deleted.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showBiometricConsentDialog(onAgree: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Biometric Voice Recognition Consent")
            .setMessage(
                "Before enrolling, please review how Siya uses your voice data:\n\n" +
                "• What is stored: Numeric voice vectors (128 mathematical numbers representing your vocal frequency), NOT your audio recordings.\n" +
                "• Audio is never saved: Raw audio from enrollment is deleted immediately after vector extraction.\n" +
                "• Stored on-device: Vectors are encrypted in hardware Android Keystore and never leave your phone.\n" +
                "• Full control: You can delete your biometric profile at any time with one tap.\n" +
                "• Optional: Voice lock is optional; Siya works without it."
            )
            .setPositiveButton("I Agree") { _, _ ->
                preferences.voiceStorage.consentTimestamp = System.currentTimeMillis()
                onAgree()
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingSample() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        isRecording = true
        binding.progressLiveLevel.visibility = View.VISIBLE
        binding.progressLiveLevel.progress = 0
        binding.btnRecordSample.text = "STOP & PROCESS"
        binding.btnRecordSample.setBackgroundColor(getColor(R.color.status_amber))
        binding.tvRecordFeedback.text = "Listening... Speak the phrase above naturally for 4-6 seconds!"

        startAudioCapture { pcmChunk ->
            recordingBuffer.write(pcmChunk)
            val rms = AudioUtils.calculateRmsNormalized(pcmChunk, pcmChunk.size)
            val levelPercent = (rms * 2500f).toInt().coerceIn(0, 100)
            runOnUiThread {
                binding.progressLiveLevel.progress = levelPercent
            }
        }
    }

    private fun stopRecordingSample() {
        isRecording = false
        stopAudioCapture()
        binding.progressLiveLevel.visibility = View.GONE
        val pcmAudio = recordingBuffer.toByteArray()
        recordingBuffer.reset()

        // Quality Gate Check (2B)
        val quality = OwnerVoiceManager.checkQualityGate(pcmAudio)
        if (!quality.passed) {
            binding.btnRecordSample.isEnabled = true
            binding.btnRecordSample.setBackgroundColor(getColor(R.color.primary_cyan))
            val reasonMsg = when (quality.reason) {
                "too short" -> "Too short: Needs at least 3.0s of voiced speech (recorded %.1fs).".format(quality.voicedSeconds)
                "too quiet" -> "Too quiet: Please speak louder into the microphone."
                "too noisy" -> "Too noisy: Background noise too high (SNR: %.1fdB, needs >= 15dB).".format(quality.snrDb)
                "audio clipped" -> "Audio clipped: Microphone level too high (%.1f%% clipped). Speak further from mic.".format(quality.clippingPercent)
                else -> quality.reason
            }
            binding.tvRecordFeedback.text = reasonMsg
            binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
            Toast.makeText(this, "Sample rejected: ${quality.reason}. Please redo.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnRecordSample.isEnabled = false
        binding.tvRecordFeedback.text = "Quality gate passed (Voiced: %.1fs, SNR: %.1fdB). Extracting vector...".format(quality.voicedSeconds, quality.snrDb)

        lifecycleScope.launch(Dispatchers.Default) {
            val vector = OwnerVoiceManager.extractSpeakerVector(pcmAudio)
            withContext(Dispatchers.Main) {
                binding.btnRecordSample.isEnabled = true
                binding.btnRecordSample.setBackgroundColor(getColor(R.color.primary_cyan))

                if (vector != null && vector.isNotEmpty()) {
                    recordedVectors.add(vector)

                    // Consistency check after sample 3 (2B)
                    if (recordedVectors.size >= 3) {
                        val consistency = OwnerVoiceManager.checkConsistency(recordedVectors)
                        binding.tvVoiceProfileQuality.visibility = View.VISIBLE
                        binding.tvVoiceProfileQuality.text = "Voice profile quality: ${consistency.qualityLabel}"

                        if (!consistency.isConsistent && consistency.outlierIndex != null) {
                            val badIdx = consistency.outlierIndex
                            recordedVectors.removeAt(badIdx)
                            Toast.makeText(this@VoiceEnrollmentActivity, "Sample inconsistent with previous recordings. Please redo.", Toast.LENGTH_LONG).show()
                            binding.tvRecordFeedback.text = "Sample varied too much from your normal voice. Redo sample ${currentSampleIndex + 1}."
                            binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
                            return@withContext
                        }
                    }

                    currentSampleIndex++
                    if (currentSampleIndex >= samplePrompts.size) {
                        // All 5 samples collected: Calibrate threshold T
                        val calibratedT = OwnerVoiceManager.calibrateThreshold(recordedVectors)
                        preferences.saveOwnerVoiceProfile(recordedVectors, calibratedT)
                        preferences.ownerVoiceThreshold = calibratedT
                        preferences.isOwnerVoiceEnabled = true // Enable now that calibration succeeded

                        Toast.makeText(this@VoiceEnrollmentActivity, "Enrolled 5 samples! Calibrated threshold T=%.2f".format(calibratedT), Toast.LENGTH_LONG).show()
                        setupUI()
                    } else {
                        Toast.makeText(this@VoiceEnrollmentActivity, "Sample $currentSampleIndex saved! Say next phrase.", Toast.LENGTH_SHORT).show()
                        updateEnrollmentStepUI()
                    }
                } else {
                    binding.tvRecordFeedback.text = "Could not extract speaker features. Please speak louder and retry."
                    binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTestingVoice() {
        if (!preferences.hasOwnerVoiceProfile()) {
            Toast.makeText(this, "Please enroll your 5 voice samples first!", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        isTesting = true
        binding.btnTestVoice.text = "STOP & VERIFY NOW"
        val amberColor = ContextCompat.getColor(this, R.color.status_amber)
        binding.btnTestVoice.setTextColor(amberColor)
        binding.btnTestVoice.strokeColor = android.content.res.ColorStateList.valueOf(amberColor)
        binding.layoutTestResult.visibility = View.GONE

        startAudioCapture { pcmChunk ->
            recordingBuffer.write(pcmChunk)
        }
    }

    private fun stopTestingVoice() {
        isTesting = false
        stopAudioCapture()
        val pcmAudio = recordingBuffer.toByteArray()
        recordingBuffer.reset()

        binding.btnTestVoice.text = "TEST VOICE MATCH (SPEAK NOW)"
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.primary_cyan)
        binding.btnTestVoice.setTextColor(accentColor)
        binding.btnTestVoice.strokeColor = android.content.res.ColorStateList.valueOf(accentColor)

        if (pcmAudio.size < 48000) { // Under 1.5s
            binding.layoutTestResult.visibility = View.VISIBLE
            binding.tvTestVerdict.text = "RECORDING TOO SHORT"
            binding.tvTestVerdict.setTextColor(getColor(R.color.status_amber))
            binding.tvTestDetails.text = "Please speak for ~4 seconds to evaluate speaker biometrics."
            binding.progressMatchStrength.progress = 0
            return
        }

        binding.btnTestVoice.isEnabled = false
        lifecycleScope.launch(Dispatchers.Default) {
            val result = OwnerVoiceManager.verifySpeakerAudio(pcmAudio, preferences)

            // Save result to encrypted test history
            preferences.voiceStorage.addTestResult(
                VoiceSecurityStorage.VoiceTestEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = result.distance,
                    verdict = result.status.name
                )
            )

            withContext(Dispatchers.Main) {
                binding.btnTestVoice.isEnabled = true
                binding.layoutTestResult.visibility = View.VISIBLE

                binding.progressMatchStrength.progress = result.similarityPercent
                binding.tvMatchStrengthLabel.text = "Match Strength: ${result.similarityPercent}%"

                when (result.status) {
                    VoiceMatchStatus.OWNER -> {
                        binding.tvTestVerdict.text = "VERDICT: OWNER (${result.similarityPercent}% STRENGTH)"
                        binding.tvTestVerdict.setTextColor(getColor(R.color.status_green))
                        binding.progressMatchStrength.progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.status_green))
                        binding.tvTestDetails.text = "Owner verified. Distance: %.3f (Calibrated T: %.2f)".format(result.distance, result.threshold)
                    }
                    VoiceMatchStatus.UNCERTAIN -> {
                        binding.tvTestVerdict.text = "VERDICT: UNCERTAIN (${result.similarityPercent}% STRENGTH)"
                        binding.tvTestVerdict.setTextColor(getColor(R.color.status_amber))
                        binding.progressMatchStrength.progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.status_amber))
                        binding.tvTestDetails.text = "Borderline score: %.3f near threshold %.2f (%s)".format(result.distance, result.threshold, result.reason)
                    }
                    VoiceMatchStatus.NOT_OWNER -> {
                        binding.tvTestVerdict.text = "VERDICT: NOT OWNER (${result.similarityPercent}% STRENGTH)"
                        binding.tvTestVerdict.setTextColor(getColor(R.color.status_red))
                        binding.progressMatchStrength.progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.status_red))
                        binding.tvTestDetails.text = "Stranger detected. Distance: %.3f exceeds threshold %.2f + 0.08".format(result.distance, result.threshold)
                    }
                }

                checkFrequentRejections()
            }
        }
    }

    private fun checkFrequentRejections() {
        val rejections = preferences.voiceStorage.getRecentRejectionCount(5)
        if (rejections > 2) {
            binding.tvSuggestionRelaxed.visibility = View.VISIBLE
            binding.tvSuggestionRelaxed.text = "Tip: $rejections of last 5 tests were rejected. Try selecting 'Relaxed' sensitivity."
        } else {
            binding.tvSuggestionRelaxed.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(onChunk: (ByteArray) -> Unit) {
        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start audio recording: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive && (isRecording || isTesting)) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    onChunk(chunk)
                }
            }
        }
    }

    private fun stopAudioCapture() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioCapture()
    }
}
