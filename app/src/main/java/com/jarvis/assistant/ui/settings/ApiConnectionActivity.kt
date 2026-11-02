package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import com.jarvis.assistant.network.GeminiLiveWebSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val testHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadCurrentPreferences()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentPreferences() {
        binding.etApiKey.setText(preferences.apiKey)
        binding.etCustomEndpoint.setText(preferences.customEndpoint)

        when (preferences.aiModel) {
            GeminiConstants.MODEL_3_8_LIVE -> binding.rbModelFlash20.isChecked = true
            GeminiConstants.MODEL_3_8_LIVE_EXTENDED -> binding.rbModelFlash15.isChecked = true
            else -> binding.rbModelFlashThinking.isChecked = true
        }

        if (preferences.apiKey.isNotBlank()) {
            binding.tvLiveStatus.text = "KEY CONFIGURED • READY"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            binding.tvLiveStatus.text = "NO API KEY • ENTER KEY BELOW"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
        }

        if (preferences.customEndpoint.isNotBlank()) {
            binding.tvEndpointSummary.text = "Active Endpoint: ${preferences.customEndpoint}"
        }
    }

    private fun setupListeners() {
        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.text?.toString()?.trim() ?: ""
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                Toast.makeText(this, "API Key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener {
            binding.etApiKey.setText("")
        }

        binding.btnTestConnection.setOnClickListener {
            val enteredKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (enteredKey.isBlank()) {
                Snackbar.make(binding.root, "Please enter an API key first", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testConnection(enteredKey)
        }

        binding.btnTestVoiceConnection.setOnClickListener {
            val enteredKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (enteredKey.isBlank()) {
                showVoiceResult(false, "FAIL (Stage 0: Key Validation): API key is empty. Enter your key first.")
                return@setOnClickListener
            }
            testVoiceConnection(enteredKey)
        }

        binding.btnTestImageGeneration.setOnClickListener {
            val geminiKey = binding.etApiKey.text?.toString()?.trim() ?: preferences.apiKey

            binding.btnTestImageGeneration.isEnabled = false
            binding.tvImageTestResult.visibility = View.VISIBLE
            binding.tvImageTestResult.setTextColor(Color.parseColor("#3B82F6"))
            binding.tvImageTestResult.text = "Testing key-free image generation chain (Gemini -> Pollinations -> AI Horde)..."

            lifecycleScope.launch(Dispatchers.IO) {
                val client = com.jarvis.assistant.network.ImageGenClient()
                val result = client.generateImage(
                    context = applicationContext,
                    apiKey = geminiKey,
                    prompt = "A serene mountain lake at sunrise, highly detailed"
                )
                withContext(Dispatchers.Main) {
                    binding.btnTestImageGeneration.isEnabled = true
                    when (result) {
                        is com.jarvis.assistant.network.ImageGenResult.Success -> {
                            binding.tvImageTestResult.setTextColor(Color.parseColor("#22C55E"))
                            binding.tvImageTestResult.text = "SUCCESS • Generated via ${result.modelUsed} (${result.imageFile.name})"
                        }
                        is com.jarvis.assistant.network.ImageGenResult.Failure -> {
                            binding.tvImageTestResult.setTextColor(Color.parseColor("#EF4444"))
                            binding.tvImageTestResult.text = "FAILED • ${result.reason}"
                        }
                    }
                }
            }
        }

        binding.btnSaveConnection.setOnClickListener {
            saveAndApply()
        }
    }

    private var testWebSocket: GeminiLiveWebSocket? = null

    private fun showVoiceResult(success: Boolean, message: String) {
        binding.tvVoiceTestResult.visibility = View.VISIBLE
        binding.tvVoiceTestResult.text = message
        val color = if (success) Color.parseColor("#22C55E") else Color.parseColor("#EF4444")
        binding.tvVoiceTestResult.setTextColor(color)
    }

    private fun testVoiceConnection(apiKey: String) {
        binding.btnTestVoiceConnection.isEnabled = false
        binding.tvVoiceTestResult.visibility = View.VISIBLE
        binding.tvVoiceTestResult.setTextColor(Color.parseColor("#3B82F6"))
        binding.tvVoiceTestResult.text = "Testing voice connection: Stage 1/5 - Testing mic sound capture..."

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_8_LIVE_EXTENDED
            else -> GeminiConstants.MODEL_3_1_FLASH
        }
        val voice = preferences.voice

        lifecycleScope.launch {
            // Stage 1: Mic Sound Capture (1 second)
            val micPass = withContext(Dispatchers.IO) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this@ApiConnectionActivity, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return@withContext Pair(false, "Microphone permission not granted.")
                }
                var maxAmp = 0f
                var totalBytes = 0
                val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufferSize = maxOf(minBuffer * 2, 16000)
                var recorder: AudioRecord? = null
                try {
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        return@withContext Pair(false, "AudioRecord failed to initialize.")
                    }
                    recorder.startRecording()
                    val buf = ByteArray(640)
                    val startRec = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startRec < 1000L) {
                        val read = recorder.read(buf, 0, buf.size)
                        if (read > 0) {
                            totalBytes += read
                            val freq = com.jarvis.assistant.audio.AudioUtils.analyzeFrequency(buf, read, 16000, 1.0f)
                            if (freq.amplitude > maxAmp) maxAmp = freq.amplitude
                        }
                    }
                    recorder.stop()
                    recorder.release()
                    if (totalBytes == 0 || maxAmp < 0.0001f) {
                        Pair(false, "Mic captured only zero RMS / silence (amp=$maxAmp, bytes=$totalBytes).")
                    } else {
                        Pair(true, "Captured sound successfully (RMS=${"%.4f".format(maxAmp)}, $totalBytes bytes).")
                    }
                } catch (e: Exception) {
                    try { recorder?.release() } catch (_: Exception) {}
                    Pair(false, "Mic capture exception: ${e.message}")
                }
            }

            if (!micPass.first) {
                binding.btnTestVoiceConnection.isEnabled = true
                showVoiceResult(false, "FAIL (Stage 1: Mic Capture): ${micPass.second}")
                return@launch
            }

            binding.tvVoiceTestResult.text = "Stage 1 PASS (Mic captured sound). Stage 2/5 - Connecting to Gemini Live..."

            val audioBytesCollected = mutableListOf<ByteArray>()
            var stage = "Stage 2 (Handshake)"
            var completed = false
            var failureReason: String? = null

            val testListener = object : GeminiLiveWebSocket.Listener {
                override fun onConnectionStateChanged(status: String) {
                    if (completed) return
                    binding.tvVoiceTestResult.text = "Testing voice: $status"
                    if (status == "LIVE") {
                        stage = "Stage 3 (Text Greeting Turn)"
                        binding.tvVoiceTestResult.text = "Stage 2 PASS (Handshake OK). Stage 3/5 - Sending text greeting turn..."
                        testWebSocket?.sendClientContent("Say hello in one short sentence")
                    }
                }

                override fun onAudioDataReceived(pcmData: ByteArray) {
                    if (completed) return
                    audioBytesCollected.add(pcmData)
                    stage = "Stage 4 (Receiving Audio)"
                    binding.tvVoiceTestResult.text = "Stage 4/5 - Receiving audio (${audioBytesCollected.sumOf { it.size }} bytes)..."
                }

                override fun onAssistantTextReceived(textChunk: String) {}
                override fun onUserTextReceived(textChunk: String) {}
                override fun onInterrupted() {}

                override fun onTurnCompleted() {
                    if (completed) return
                    stage = "Stage 4 (Turn Complete)"
                }

                override fun onError(message: String, isFatal: Boolean, errorCode: String?) {
                    if (completed) return
                    failureReason = message
                }

                override fun onToolCall(callId: String, name: String, args: JSONObject) {}
            }

            val testWs = GeminiLiveWebSocket(
                apiKey = apiKey,
                model = selectedModel,
                voiceName = voice,
                systemPrompt = "You are Siya Ai. Reply briefly in a friendly female voice in one short sentence.",
                listener = testListener
            )
            testWebSocket = testWs
            testWs.connect(lifecycleScope)

            // Wait up to 12 seconds for handshake, turn, and audio chunks
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 12000L) {
                delay(200)
                if (failureReason != null) break
                if (audioBytesCollected.isNotEmpty() && (stage == "Stage 4 (Turn Complete)" || System.currentTimeMillis() - startTime > 6000L)) {
                    break
                }
            }

            completed = true
            testWs.disconnect()
            testWebSocket = null
            binding.btnTestVoiceConnection.isEnabled = true

            if (failureReason != null) {
                showVoiceResult(false, "FAIL ($stage): $failureReason")
                return@launch
            }

            val totalBytes = audioBytesCollected.sumOf { it.size }
            if (totalBytes == 0) {
                showVoiceResult(false, "FAIL (Stage 4: Audio Received): No audio chunks returned by Gemini Live within timeout.")
                return@launch
            }

            binding.tvVoiceTestResult.text = "Stage 4 PASS (Received $totalBytes bytes). Stage 5/5 - Testing AudioTrack playback..."

            // Stage 5: Audio played
            var playedSuccessfully = false
            withContext(Dispatchers.IO) {
                try {
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(24000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(totalBytes.coerceAtLeast(48000))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    track.play()
                    for (chunk in audioBytesCollected) {
                        track.write(chunk, 0, chunk.size)
                    }
                    delay(300)
                    track.stop()
                    track.release()
                    playedSuccessfully = true
                } catch (e: Exception) {
                    Log.e("ApiConnection", "Error playing test audio: ${e.message}", e)
                    playedSuccessfully = false
                }
            }

            if (playedSuccessfully) {
                showVoiceResult(true, "PASS (5/5 Stages): Mic captured sound, Live handshake complete, greeting sent, received $totalBytes bytes of neural speech, and played aloud ($voice).")
            } else {
                showVoiceResult(false, "FAIL (Stage 5: Audio Playback): Received $totalBytes bytes but AudioTrack failed to play.")
            }
        }
    }

    private fun testConnection(apiKey: String) {
        binding.btnTestConnection.isEnabled = false
        binding.tvPingResult.text = "Testing ping..."
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                    val request = Request.Builder().url(url).build()
                    testHttpClient.newCall(request).execute().use { response ->
                        val duration = System.currentTimeMillis() - startTime
                        if (response.isSuccessful) {
                            Pair(true, duration)
                        } else {
                            Pair(false, response.code)
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, -1)
                }
            }

            binding.btnTestConnection.isEnabled = true
            if (result.first) {
                val ping = result.second
                binding.tvPingResult.text = "Ping: ${ping}ms • Valid"
                binding.tvLiveStatus.text = "CONNECTED • HANDSHAKE OK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "Connection verified! Ping: ${ping}ms", Snackbar.LENGTH_LONG).show()
            } else {
                val code = result.second
                binding.tvPingResult.text = if (code == -1) "Network Timeout" else "HTTP $code Error"
                binding.tvLiveStatus.text = "FAILED • INVALID KEY OR NETWORK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, "Connection failed. Please verify API key.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndApply() {
        val key = binding.etApiKey.text?.toString()?.trim() ?: ""
        val customEp = binding.etCustomEndpoint.text?.toString()?.trim() ?: ""

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_8_LIVE_EXTENDED
            else -> GeminiConstants.MODEL_3_1_FLASH
        }

        preferences.apiKey = key
        preferences.aiModel = selectedModel
        preferences.customEndpoint = customEp

        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
        com.jarvis.assistant.background.JarvisConversationService.restartSession()

        Toast.makeText(this, "API settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        testWebSocket?.disconnect()
        testWebSocket = null
    }
}
