package com.jarvis.assistant.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.VoiceMatchStatus
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.ActivityMicVoiceTestBinding
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

class MicVoiceTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMicVoiceTestBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMicVoiceTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupListeners()
        setupHearingTest()
        updateSensitivityUI(preferences.micSensitivity)
        observeDiagnostics()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        binding.tvUnifiedFooter.setOnClickListener {
            com.jarvis.assistant.util.ExternalLinks.open(this, com.jarvis.assistant.util.BrandConfig.TELEGRAM_CHANNEL_URL)
        }

        binding.chipSensitivityLow.setOnClickListener {
            setSensitivity("low")
        }

        binding.chipSensitivityNormal.setOnClickListener {
            setSensitivity("normal")
        }

        binding.chipSensitivityHigh.setOnClickListener {
            setSensitivity("high")
        }
    }

    private fun setSensitivity(level: String) {
        preferences.micSensitivity = level
        updateSensitivityUI(level)
        JarvisConversationService.activeInstance?.let { service ->
            val gain = when (level) {
                "high" -> 1.4f
                "low" -> 0.8f
                else -> 1.0f
            }
            service.audioRecorder?.sensitivityGain = gain
        }
    }

    private fun updateSensitivityUI(current: String) {
        val selectedBg = R.drawable.bg_chip_selected
        val unselectedBg = R.drawable.bg_chip_unselected
        val accentColor = ContextCompat.getColor(this, R.color.accent_sapphire)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.chipSensitivityLow.setBackgroundResource(if (current == "low") selectedBg else unselectedBg)
        binding.chipSensitivityLow.setTextColor(if (current == "low") accentColor else mutedColor)

        binding.chipSensitivityNormal.setBackgroundResource(if (current == "normal") selectedBg else unselectedBg)
        binding.chipSensitivityNormal.setTextColor(if (current == "normal") accentColor else mutedColor)

        binding.chipSensitivityHigh.setBackgroundResource(if (current == "high") selectedBg else unselectedBg)
        binding.chipSensitivityHigh.setTextColor(if (current == "high") accentColor else mutedColor)
    }

    private fun observeDiagnostics() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Audio level
                launch {
                    JarvisConversationService.audioLevel.collect { level ->
                        val percent = (level * 100).toInt().coerceIn(0, 100)
                        binding.progressMicLevel.progress = percent
                        binding.tvMicLevelPercent.text = "Audio Level: $percent%"
                    }
                }

                // VAD state
                launch {
                    JarvisConversationService.isVadSpeechActive.collect { isSpeech ->
                        if (isSpeech) {
                            binding.tvVadStateBadge.text = "VOICE ACTIVE"
                            binding.tvVadStateBadge.setTextColor(Color.parseColor("#10B981"))
                            binding.tvVadStateBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
                        } else {
                            binding.tvVadStateBadge.text = "SILENCE"
                            binding.tvVadStateBadge.setTextColor(ContextCompat.getColor(this@MicVoiceTestActivity, R.color.text_secondary))
                            binding.tvVadStateBadge.setBackgroundResource(R.drawable.bg_jarvis_chip)
                        }
                    }
                }

                // Active Audio Source
                launch {
                    val recorder = JarvisConversationService.activeInstance?.audioRecorder
                    recorder?.activeAudioSourceName?.collect { sourceName ->
                        binding.tvActiveMicSource.text = sourceName
                    } ?: run {
                        binding.tvActiveMicSource.text = "VOICE_COMMUNICATION"
                    }
                }

                // Voice Verification Result
                launch {
                    JarvisConversationService.lastVoiceVerificationResult.collect { result ->
                        if (result != null) {
                            when (result.status) {
                                VoiceMatchStatus.OWNER -> {
                                    binding.tvOwnerMatchPercent.text = "${result.similarityPercent}% (Owner)"
                                    binding.tvOwnerMatchPercent.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvLastRejectReason.text = "Allowed: ${result.reason}"
                                    binding.tvLastRejectReason.setTextColor(Color.parseColor("#10B981"))
                                }
                                VoiceMatchStatus.UNCERTAIN -> {
                                    binding.tvOwnerMatchPercent.text = "${result.similarityPercent}% (Uncertain)"
                                    binding.tvOwnerMatchPercent.setTextColor(ContextCompat.getColor(this@MicVoiceTestActivity, R.color.accent_sapphire))
                                    binding.tvLastRejectReason.text = "Borderline / Short: ${result.reason}"
                                    binding.tvLastRejectReason.setTextColor(ContextCompat.getColor(this@MicVoiceTestActivity, R.color.accent_sapphire))
                                }
                                VoiceMatchStatus.NOT_OWNER -> {
                                    binding.tvOwnerMatchPercent.text = "${result.similarityPercent}% (Not Owner)"
                                    binding.tvOwnerMatchPercent.setTextColor(Color.parseColor("#EF4444"))
                                    binding.tvLastRejectReason.text = "Blocked: ${result.reason}"
                                    binding.tvLastRejectReason.setTextColor(Color.parseColor("#EF4444"))
                                }
                            }
                        } else {
                            if (!preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()) {
                                binding.tvOwnerMatchPercent.text = "All Allowed (Lock Off)"
                                binding.tvOwnerMatchPercent.setTextColor(Color.parseColor("#10B981"))
                                binding.tvLastRejectReason.text = "Owner voice lock is disabled"
                            } else {
                                binding.tvOwnerMatchPercent.text = "Waiting for speech..."
                                binding.tvLastRejectReason.text = "No utterance verified yet"
                            }
                        }
                    }
                }

                // Vosk Speaker Model State (Truthful: READY / DOWNLOADING / MISSING / CORRUPT)
                launch {
                    VoskModelManager.speakerModelStatus.collect { status ->
                        binding.tvSpeakerModelStatus.text = status.name
                        when (status) {
                            com.jarvis.assistant.audio.SpeakerModelStatus.READY -> {
                                binding.tvSpeakerModelStatus.setTextColor(Color.parseColor("#10B981"))
                            }
                            com.jarvis.assistant.audio.SpeakerModelStatus.DOWNLOADING -> {
                                binding.tvSpeakerModelStatus.setTextColor(ContextCompat.getColor(this@MicVoiceTestActivity, R.color.accent_sapphire))
                            }
                            com.jarvis.assistant.audio.SpeakerModelStatus.MISSING -> {
                                binding.tvSpeakerModelStatus.setTextColor(Color.parseColor("#F59E0B"))
                            }
                            com.jarvis.assistant.audio.SpeakerModelStatus.CORRUPT -> {
                                binding.tvSpeakerModelStatus.setTextColor(Color.parseColor("#EF4444"))
                            }
                        }
                    }
                }

                // Live Session State
                launch {
                    JarvisConversationService.connectionStatus.collect { status ->
                        binding.tvLiveSessionState.text = status
                    }
                }

                // Live Pipeline Counters & Telemetry
                launch {
                    com.jarvis.assistant.audio.MicDiagnosticsTracker.snapshot.collect { s ->
                        binding.tvCounterFps.text = "${s.framesCapturedPerSec} fps"
                        binding.tvCounterRms.text = "%.4f".format(s.lastRms)
                        binding.tvDroppedSessionOff.text = s.droppedSessionOff.toString()
                        binding.tvDroppedOwnPlayback.text = s.droppedOwnPlayback.toString()
                        binding.tvDroppedTailGuard.text = s.droppedTailGuard.toString()
                        binding.tvDroppedSessionNotReady.text = s.droppedSessionNotReady.toString()
                        binding.tvDroppedOwnerPending.text = s.droppedOwnerPending.toString()
                        binding.tvDroppedMuted.text = s.droppedMuted.toString()

                        val kbSent = s.bytesSent / 1024
                        binding.tvChunksBytesSent.text = "${s.chunksSent} chunks ($kbSent KB)"
                        binding.tvServerMessages.text = s.serverMessagesReceived.toString()
                        binding.tvInputTranscriptions.text = s.inputTranscriptionsReceived.toString()
                        binding.tvModelTurns.text = s.modelTurns.toString()
                        binding.tvLastGeminiHeard.text = s.lastGeminiHeardText.ifBlank { "(None yet)" }
                    }
                }
            }
        }
    }

    private var hearingTestJob: kotlinx.coroutines.Job? = null

    private fun setupHearingTest() {
        binding.btnCopyDiagnostics.setOnClickListener {
            val copyText = com.jarvis.assistant.audio.MicDiagnosticsTracker.getCopyText()
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Siya Mic Diagnostics", copyText))
            android.widget.Toast.makeText(this, "Diagnostics counters copied (transcripts omitted)", android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.btnStartHearingTest.setOnClickListener {
            startHearingTest()
        }
    }

    private fun startHearingTest() {
        hearingTestJob?.cancel()
        binding.layoutHearingTestResult.visibility = View.VISIBLE
        binding.tvHearingTestStatus.text = "TESTING IN PROGRESS..."
        binding.tvHearingTestStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_sapphire))
        binding.tvHearingTestDetail.text = "Please speak a phrase into the mic now (e.g. 'Hey Siya what time is it')..."
        binding.btnStartHearingTest.isEnabled = false

        if (!JarvisConversationService.isSessionOn.value) {
            JarvisConversationService.startSession(this)
        }
        JarvisConversationService.ensureAudioCapture()

        val initialSnapshot = com.jarvis.assistant.audio.MicDiagnosticsTracker.snapshot.value
        var maxObservedRms = 0f

        hearingTestJob = lifecycleScope.launch {
            for (sec in 8 downTo 1) {
                binding.btnStartHearingTest.text = "LISTENING... (${sec}s)"
                val currentRms = com.jarvis.assistant.audio.MicDiagnosticsTracker.snapshot.value.lastRms
                if (currentRms > maxObservedRms) {
                    maxObservedRms = currentRms
                }
                kotlinx.coroutines.delay(1000L)
            }

            val finalSnapshot = com.jarvis.assistant.audio.MicDiagnosticsTracker.snapshot.value
            binding.btnStartHearingTest.isEnabled = true
            binding.btnStartHearingTest.text = "START HEARING TEST"

            // Evaluate failure stage precisely
            val diffChunks = finalSnapshot.chunksSent - initialSnapshot.chunksSent
            val diffTranscripts = finalSnapshot.inputTranscriptionsReceived - initialSnapshot.inputTranscriptionsReceived
            val diffTurns = finalSnapshot.modelTurns - initialSnapshot.modelTurns
            val droppedPlayback = finalSnapshot.droppedOwnPlayback - initialSnapshot.droppedOwnPlayback
            val droppedTail = finalSnapshot.droppedTailGuard - initialSnapshot.droppedTailGuard
            val droppedSessionOff = finalSnapshot.droppedSessionOff - initialSnapshot.droppedSessionOff
            val droppedMuted = finalSnapshot.droppedMuted - initialSnapshot.droppedMuted
            val droppedOwner = finalSnapshot.droppedOwnerPending - initialSnapshot.droppedOwnerPending
            val droppedNotReady = finalSnapshot.droppedSessionNotReady - initialSnapshot.droppedSessionNotReady

            when {
                maxObservedRms < 0.005f -> {
                    binding.tvHearingTestStatus.text = "FAIL: MIC SILENT"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "Microphone recorded zero audio (max RMS < 0.005). Check mic permissions, hardware, or headset connection."
                }
                droppedMuted > 0 -> {
                    binding.tvHearingTestStatus.text = "FAIL: FRAMES DROPPED BY MUTED"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "Microphone is muted in the app. Unmute to transmit audio."
                }
                droppedSessionOff > 0 -> {
                    binding.tvHearingTestStatus.text = "FAIL: FRAMES DROPPED BY SESSION_OFF"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "Conversation service session is not started."
                }
                droppedPlayback > 10 -> {
                    binding.tvHearingTestStatus.text = "FAIL: FRAMES DROPPED BY OWN_PLAYBACK"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "Assistant speaker gate suppressed mic frames ($droppedPlayback dropped)."
                }
                droppedTail > 10 -> {
                    binding.tvHearingTestStatus.text = "FAIL: FRAMES DROPPED BY TAIL_GUARD"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "150ms echo suppression tail was active ($droppedTail frames dropped)."
                }
                droppedOwner > 5 -> {
                    binding.tvHearingTestStatus.text = "FAIL: FRAMES DROPPED BY OWNER_PENDING"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "Voice lock is waiting for owner verification before streaming."
                }
                droppedNotReady > 5 && diffChunks == 0L -> {
                    binding.tvHearingTestStatus.text = "FAIL: FRAMES DROPPED BY SESSION_NOT_READY"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "WebSocket session handshake is still connecting or reconnecting."
                }
                diffChunks == 0L -> {
                    binding.tvHearingTestStatus.text = "FAIL: NO CHUNKS TRANSMITTED"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "Audio was captured locally but no PCM chunks reached Gemini Live."
                }
                diffTranscripts == 0L -> {
                    binding.tvHearingTestStatus.text = "FAIL: SENT BUT NO TRANSCRIPT"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "Streamed $diffChunks audio chunks to Gemini, but server did not return input audio transcription."
                }
                diffTurns == 0L -> {
                    val lastHeard = finalSnapshot.lastGeminiHeardText
                    binding.tvHearingTestStatus.text = "FAIL: TRANSCRIPT RECEIVED BUT NO REPLY"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvHearingTestDetail.text = "Gemini heard: \"$lastHeard\", but did not generate a spoken model reply."
                }
                else -> {
                    binding.tvHearingTestStatus.text = "PASS: END-TO-END AUDIO WORKING"
                    binding.tvHearingTestStatus.setTextColor(Color.parseColor("#10B981"))
                    binding.tvHearingTestDetail.text = "Microphone audio was successfully captured, streamed ($diffChunks chunks), transcribed, and answered by Gemini Live!"
                }
            }
        }
    }
}
