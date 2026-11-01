package com.jarvis.assistant.ui.camera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.ActivityCameraVisionBinding
import com.jarvis.assistant.ui.home.MainViewModel
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.vision.CameraFeedManager
import com.jarvis.assistant.vision.CameraFrameProcessor
import com.jarvis.assistant.vision.ConflatedFrameQueue
import com.jarvis.assistant.vision.FrameRateThrottler
import com.jarvis.assistant.vision.ReusableByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraVisionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraVisionActivity"
        private const val TIMEOUT_PAUSE_MS = 10 * 60 * 1000L // 10 minutes
        private const val SESSION_WAIT_TIMEOUT_MS = 10_000L // 10 seconds
    }

    private lateinit var binding: ActivityCameraVisionBinding
    private val viewModel: MainViewModel by viewModels()

    private var cameraProvider: ProcessCameraProvider? = null
    private var isBackCamera = true
    private var isCameraBound = false
    private var isPausedForTimeout = false
    private var sessionWaitJob: kotlinx.coroutines.Job? = null
    private var timeoutJob: kotlinx.coroutines.Job? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val frameThrottler = FrameRateThrottler(1000L)
    private val frameQueue = ConflatedFrameQueue<Bitmap> { droppedBitmap ->
        try { droppedBitmap.recycle() } catch (_: Throwable) {}
    }
    private val reusableStream = ReusableByteArrayOutputStream(128 * 1024)
    private val isEncodingFrame = AtomicBoolean(false)
    private var lastSentThumb: IntArray? = null
    private var lastSentTimestampMs = 0L
    private var streamingStartTimeMs = 0L
    private var isFirstFramePending = false
    private var framesSentCount = 0
    private var cameraTimeoutJob: kotlinx.coroutines.Job? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i("SiyaCamera", "Camera permission result: granted=$isGranted")
        if (isGranted) {
            binding.layoutPermissionDenied.visibility = View.GONE
            startCamera()
        } else {
            showPermissionDeniedUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityCameraVisionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.previewView.previewStreamState.observe(this) { state ->
            Log.d("SiyaCamera", "Preview stream state changed: $state")
            if (state == PreviewView.StreamState.STREAMING) {
                Log.i("SiyaCamera", "Camera preview streaming active")
                cameraTimeoutJob?.cancel()
                binding.layoutCameraError.visibility = View.GONE
            }
        }

        setupListeners()
        observeState()
        checkCameraPermissionAndStart()
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startTimeoutTimer()
        if (JarvisConversationService.isStandby.value) {
            JarvisConversationService.triggerActiveListening()
        } else if (!JarvisConversationService.isSessionOn.value) {
            viewModel.toggleSession()
        }
        monitorSessionStatus()
    }

    override fun onStop() {
        super.onStop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopStreamingAndCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraTimeoutJob?.cancel()
        stopStreamingAndCamera()
        analysisExecutor.shutdown()
        frameQueue.clear()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnRetryCamera.setOnClickListener {
            binding.layoutCameraError.visibility = View.GONE
            startCamera()
        }

        binding.btnFlipCamera.setOnClickListener {
            flipCamera()
        }

        binding.btnResumeCamera.setOnClickListener {
            resumeFromTimeout()
        }

        binding.btnOpenSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        binding.btnStartSession.setOnClickListener {
            binding.btnStartSession.visibility = View.GONE
            binding.tvSessionOfflineMessage.text = "Connecting Siya..."
            viewModel.toggleSession()
            monitorSessionStatus()
        }

        // Quick Chips
        binding.chipDescribeDetail.setOnClickListener {
            sendQuickPrompt(getString(R.string.camera_chip_describe_prompt))
        }
        binding.chipReadText.setOnClickListener {
            sendQuickPrompt(getString(R.string.camera_chip_read_prompt))
        }
        binding.chipWhatIsThis.setOnClickListener {
            sendQuickPrompt(getString(R.string.camera_chip_what_prompt))
        }
        binding.chipExplainMore.setOnClickListener {
            sendQuickPrompt(getString(R.string.camera_chip_explain_prompt))
        }

        // Copy Answer
        binding.btnCopyAnswer.setOnClickListener {
            val answer = binding.tvAnswerText.text?.toString()?.trim().orEmpty()
            if (answer.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Siya Answer", answer))
                Toast.makeText(this, "Answer copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // Bottom Input
        binding.btnSendQuestion.setOnClickListener { sendCustomQuestion() }
        binding.etQuestionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCustomQuestion()
                true
            } else {
                false
            }
        }

        // Mic Button
        binding.btnMicToggle.setOnClickListener {
            viewModel.toggleMicMute()
        }
    }

    private fun sendQuickPrompt(promptText: String) {
        JarvisConversationService.sendTextMessage(promptText)
    }

    private fun sendCustomQuestion() {
        val question = binding.etQuestionInput.text?.toString()?.trim().orEmpty()
        if (question.isNotBlank()) {
            JarvisConversationService.sendTextMessage(question)
            binding.etQuestionInput.text = null
        }
    }

    private fun checkCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                binding.layoutPermissionDenied.visibility = View.GONE
                startCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionDeniedUI()
            }
            else -> {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun showPermissionDeniedUI() {
        binding.layoutPermissionDenied.visibility = View.VISIBLE
    }

    private fun showCameraError(message: String? = null) {
        binding.layoutCameraError.visibility = View.VISIBLE
        if (!message.isNullOrBlank()) {
            binding.tvCameraErrorMessage.text = message
        }
        Log.e("SiyaCamera", "Camera error displayed: ${message ?: "Startup/stream failed"}")
    }

    private fun scheduleCameraStreamingTimeout() {
        cameraTimeoutJob?.cancel()
        cameraTimeoutJob = lifecycleScope.launch {
            delay(4000L)
            if (binding.previewView.previewStreamState.value != PreviewView.StreamState.STREAMING) {
                Log.w("SiyaCamera", "Camera error: STREAMING not reached within 4 seconds")
                showCameraError("Camera preview could not be started or is taking too long.")
            }
        }
    }

    private fun startCamera() {
        binding.layoutCameraError.visibility = View.GONE
        scheduleCameraStreamingTimeout()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e("SiyaCamera", "Camera error encountered: Failed to initialize CameraX: ${e.message}", e)
                showCameraError("Camera initialization failed.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val selector = if (isBackCamera) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(768, 768))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, imageAnalysis)
            isCameraBound = true
            Log.i("SiyaCamera", "Camera bound successfully, selector=$selector")
            updateStreamingState()
        } catch (e: Exception) {
            Log.e("SiyaCamera", "Camera error encountered: Failed to bind camera: ${e.message}", e)
            isCameraBound = false
            showCameraError("Failed to bind camera preview.")
        }
    }

    private fun flipCamera() {
        CameraFeedManager.getInstance().stop()
        JarvisConversationService.sendCameraContextNote("[CAMERA OFF]")
        binding.layoutStreamingIndicator.visibility = View.GONE

        isBackCamera = !isBackCamera
        bindCameraUseCases()

        if (isCameraBound && JarvisConversationService.isSessionOn.value && !isPausedForTimeout) {
            val cameraLabel = if (isBackCamera) "back camera" else "front camera"
            streamingStartTimeMs = SystemClock.elapsedRealtime()
            isFirstFramePending = true
            frameThrottler.reset()
            CameraFeedManager.getInstance().start()
            JarvisConversationService.sendCameraContextNote("[CAMERA ON - $cameraLabel]")
            binding.layoutStreamingIndicator.visibility = View.VISIBLE
        }
    }

    private fun updateStreamingState() {
        val isSessionActive = JarvisConversationService.isSessionOn.value
        if (isCameraBound && isSessionActive && !isPausedForTimeout) {
            if (!CameraFeedManager.getInstance().isCameraSharing.value) {
                val cameraLabel = if (isBackCamera) "back camera" else "front camera"
                streamingStartTimeMs = SystemClock.elapsedRealtime()
                isFirstFramePending = true
                frameThrottler.reset()
                CameraFeedManager.getInstance().start()
                JarvisConversationService.sendCameraContextNote("[CAMERA ON - $cameraLabel]")
            }
            binding.layoutStreamingIndicator.visibility = View.VISIBLE
            binding.layoutSessionOffline.visibility = View.GONE
        } else {
            if (CameraFeedManager.getInstance().isCameraSharing.value) {
                CameraFeedManager.getInstance().stop()
                JarvisConversationService.sendCameraContextNote("[CAMERA OFF]")
            }
            binding.layoutStreamingIndicator.visibility = View.GONE
            if (!isSessionActive) {
                binding.layoutSessionOffline.visibility = View.VISIBLE
            }
            frameQueue.clear()
            isFirstFramePending = false
        }
    }

    private fun stopStreamingAndCamera() {
        if (CameraFeedManager.getInstance().isCameraSharing.value) {
            CameraFeedManager.getInstance().stop()
            JarvisConversationService.sendCameraContextNote("[CAMERA OFF]")
        }
        binding.layoutStreamingIndicator.visibility = View.GONE
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera: ${e.message}")
        }
        isCameraBound = false
        frameQueue.clear()
        isFirstFramePending = false
        sessionWaitJob?.cancel()
        timeoutJob?.cancel()
    }

    private fun monitorSessionStatus() {
        sessionWaitJob?.cancel()
        sessionWaitJob = lifecycleScope.launch {
            if (JarvisConversationService.isSessionOn.value) {
                binding.layoutSessionOffline.visibility = View.GONE
                updateStreamingState()
                return@launch
            }

            binding.layoutSessionOffline.visibility = View.VISIBLE
            binding.tvSessionOfflineMessage.text = "Connecting Siya..."
            binding.btnStartSession.visibility = View.GONE

            var waitedMs = 0L
            while (waitedMs < SESSION_WAIT_TIMEOUT_MS) {
                delay(500L)
                waitedMs += 500L
                if (JarvisConversationService.isSessionOn.value) {
                    binding.layoutSessionOffline.visibility = View.GONE
                    updateStreamingState()
                    return@launch
                }
            }

            if (!JarvisConversationService.isSessionOn.value) {
                binding.tvSessionOfflineMessage.text = "Siya is not running. Tap to start"
                binding.btnStartSession.visibility = View.VISIBLE
            }
        }
    }

    private fun startTimeoutTimer() {
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch {
            delay(TIMEOUT_PAUSE_MS)
            if (isActive) {
                isPausedForTimeout = true
                CameraFeedManager.getInstance().stop()
                JarvisConversationService.sendCameraContextNote("[CAMERA OFF]")
                binding.layoutStreamingIndicator.visibility = View.GONE
                binding.layoutPausedOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun resumeFromTimeout() {
        isPausedForTimeout = false
        binding.layoutPausedOverlay.visibility = View.GONE
        startTimeoutTimer()
        updateStreamingState()
    }

    private fun observeState() {
        // Conversation & Session state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    JarvisConversationService.isSessionOn.collect { isSessionOn ->
                        Log.d("SiyaCamera", "Session state updated: isSessionOn=$isSessionOn")
                        updateStreamingState()
                        if (!isSessionOn) {
                            binding.chipSessionStatus.text = "Offline"
                        }
                    }
                }

                launch {
                    JarvisConversationService.connectionStatus.collect { connStatus ->
                        if (connStatus != "LIVE") {
                            binding.tvStreamingStatus.text = "Connecting voice..."
                        } else {
                            binding.tvStreamingStatus.text = "Siya can see & hear this"
                        }
                    }
                }

                launch {
                    JarvisConversationService.conversationState.collect { convState ->
                        if (JarvisConversationService.isSessionOn.value) {
                            binding.chipSessionStatus.text = when (convState) {
                                ConversationState.LISTENING -> "Listening"
                                ConversationState.THINKING -> "Thinking"
                                ConversationState.SPEAKING -> "Speaking"
                                ConversationState.IDLE -> "Listening"
                            }
                        }
                    }
                }

                // Chat answers from repository
                launch {
                    JarvisApp.instance.chatRepository.turns.collect { turns ->
                        val lastTurn = turns.lastOrNull { it.jarvisResponse.isNotBlank() }
                        if (lastTurn != null) {
                            val raw = lastTurn.jarvisResponse
                            val cleanText = raw.replace("```[a-zA-Z0-9_-]*\n".toRegex(), "")
                                .replace("```".toRegex(), "")
                                .trim()
                            binding.tvAnswerText.text = cleanText
                        }
                    }
                }

                // Mic mute state
                launch {
                    viewModel.isMicMuted.collect { isMuted ->
                        binding.btnMicToggle.setImageResource(
                            if (isMuted) R.drawable.ic_mic_mute_jarvis else R.drawable.ic_mic_jarvis
                        )
                    }
                }

            }
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            val isSharing = CameraFeedManager.getInstance().isCameraSharing.value
            val isSessionActive = JarvisConversationService.isSessionOn.value
            if (!isSharing || !isSessionActive || isPausedForTimeout) {
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (!frameThrottler.shouldProcess(now)) {
                return
            }

            val bitmap = imageProxy.toBitmap()
            frameQueue.offer(bitmap)
            triggerFrameProcessing()
        } catch (e: Throwable) {
            Log.w(TAG, "Error in image analyzer: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun triggerFrameProcessing() {
        if (isEncodingFrame.getAndSet(true)) {
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    val bitmap = frameQueue.poll() ?: break
                    try {
                        val thumb = CameraFrameProcessor.computeGrayscaleThumbnail(bitmap)
                        val now = System.currentTimeMillis()
                        val isDifferent = CameraFrameProcessor.isFrameDifferent(thumb, lastSentThumb)
                        val shouldSend = CameraFrameProcessor.shouldSendFrame(isDifferent, lastSentTimestampMs, now)

                        if (shouldSend) {
                            val (targetWidth, targetHeight) = CameraFrameProcessor.calculateDownscaledDimensions(
                                bitmap.width,
                                bitmap.height,
                                CameraFrameProcessor.MAX_FRAME_DIMENSION
                            )

                            val scaledBitmap = if (targetWidth != bitmap.width || targetHeight != bitmap.height) {
                                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                            } else {
                                bitmap
                            }

                            val jpegBytes = reusableStream.useStream { stream ->
                                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                                stream.toByteArray()
                            }

                            if (scaledBitmap !== bitmap) {
                                scaledBitmap.recycle()
                            }

                            if (isFirstFramePending && streamingStartTimeMs > 0L) {
                                val timeToFirstFrame = SystemClock.elapsedRealtime() - streamingStartTimeMs
                                Log.i("SiyaLatency", "Time to first frame after STREAMING: ${timeToFirstFrame}ms")
                                isFirstFramePending = false
                            }

                            JarvisConversationService.sendCameraFrame(jpegBytes)
                            framesSentCount++
                            if (framesSentCount % 10 == 0) {
                                Log.d("SiyaCamera", "Sent frame #$framesSentCount")
                            }
                            lastSentThumb = thumb
                            lastSentTimestampMs = now
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error processing camera frame: ${e.message}")
            } finally {
                isEncodingFrame.set(false)
                if (!frameQueue.isEmpty()) {
                    triggerFrameProcessing()
                }
            }
        }
    }
}
