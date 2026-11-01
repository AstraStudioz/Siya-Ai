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
        updateSensitivityUI(preferences.micSensitivity)
        observeDiagnostics()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
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

                // Vosk Speaker Model State
                launch {
                    VoskModelManager.isSpeakerModelReady.collect { isReady ->
                        if (isReady) {
                            binding.tvSpeakerModelStatus.text = "Ready in memory"
                            binding.tvSpeakerModelStatus.setTextColor(Color.parseColor("#10B981"))
                        } else {
                            binding.tvSpeakerModelStatus.text = "Not ready / Offline"
                            binding.tvSpeakerModelStatus.setTextColor(Color.parseColor("#F59E0B"))
                        }
                    }
                }

                // Live Session State
                launch {
                    JarvisConversationService.connectionStatus.collect { status ->
                        binding.tvLiveSessionState.text = status
                    }
                }
            }
        }
    }
}
