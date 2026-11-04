package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.util.BrandConfig
import com.jarvis.assistant.util.ExternalLinks
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        loadUserData()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun loadUserData() {
        val userName = preferences.userName.ifBlank { BrandConfig.DEFAULT_OPERATOR_NAME }
        binding.tvProfileName.text = userName
        binding.tvProfileSubtitle.text = "SIYA AI OPERATOR • PRIMARY USER"

        // Owner Voice Profile Status
        val hasVoiceProfile = preferences.hasOwnerVoiceProfile()
        val spkStatus = com.jarvis.assistant.audio.VoskModelManager.speakerModelStatus.value
        if (hasVoiceProfile && spkStatus == com.jarvis.assistant.audio.SpeakerModelStatus.READY) {
            binding.tvOwnerVoiceBadge.text = "ACTIVE"
            binding.tvOwnerVoiceBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvOwnerVoiceSubtitle.text = "Voice Profile Active • Only you can speak"

            binding.tvProfileVoiceStatus.text = "VOICE ENROLLED"
            binding.tvProfileVoiceStatus.setTextColor(Color.parseColor("#10B981"))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        } else {
            val badgeText = when (spkStatus) {
                com.jarvis.assistant.audio.SpeakerModelStatus.READY -> "SETUP"
                com.jarvis.assistant.audio.SpeakerModelStatus.DOWNLOADING -> "DOWNLOADING"
                com.jarvis.assistant.audio.SpeakerModelStatus.CORRUPT -> "CORRUPT"
                com.jarvis.assistant.audio.SpeakerModelStatus.MISSING -> "INSTALL"
            }
            binding.tvOwnerVoiceBadge.text = badgeText
            binding.tvOwnerVoiceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = when (spkStatus) {
                com.jarvis.assistant.audio.SpeakerModelStatus.READY -> "Speaker Model: READY • Tap to record voice profile"
                com.jarvis.assistant.audio.SpeakerModelStatus.DOWNLOADING -> "Speaker Model: DOWNLOADING • Please wait"
                com.jarvis.assistant.audio.SpeakerModelStatus.CORRUPT -> "Speaker Model: CORRUPT • Tap to reinstall"
                com.jarvis.assistant.audio.SpeakerModelStatus.MISSING -> "Speaker Model: MISSING • Tap to install (14 MB)"
            }

            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
            )
        }

        // Language
        binding.tvCurrentLanguage.text = preferences.language

        // Persona & Voice
        val currentPersona = preferences.personality
        val currentVoice = preferences.voice
        binding.tvCurrentPersona.text = "$currentPersona • Voice: $currentVoice"
        binding.tvProfilePersonaChip.text = currentPersona.uppercase()

        // Silent Execution Mode
        binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode

        // Auto-switch chat mode
        binding.switchAutoSwitchChatMode.isChecked = preferences.isAutoSwitchChatModeEnabled

        // Model
        val modelDisplay = preferences.aiModel.removePrefix("models/")
        binding.tvCurrentModel.text = "$modelDisplay • Live WebSocket"

        // Theme
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Light Theme"
        }

        binding.tvMicSensitivitySubtitle.text = "Sensitivity: ${preferences.micSensitivity.replaceFirstChar { it.uppercase() }} • Level meter & VAD test"

        // Updates
        binding.tvAppCurrentVersion.text = "Installed: v${com.jarvis.assistant.BuildConfig.VERSION_NAME} (Build ${com.jarvis.assistant.BuildConfig.VERSION_CODE})"
        binding.switchAutoCheckUpdates.isChecked = preferences.isUpdateAutoCheckEnabled
        binding.switchUpdateNotifications.isChecked = preferences.isUpdateNotificationsEnabled
        binding.switchWifiOnlyDownload.isChecked = preferences.isUpdateWifiOnly
    }

    private fun setupClickListeners() {
        // Profile Card -> Change Name
        binding.cardProfile.setOnClickListener {
            showEditNameDialog()
        }

        // Voice Status Chip on Profile Card -> Opens Voice Enrollment
        binding.chipProfileVoiceStatus.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // Auto-switch chat mode
        binding.switchAutoSwitchChatMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isAutoSwitchChatModeEnabled = isChecked
            val msg = if (isChecked) "Auto-switch chat mode Enabled" else "Auto-switch chat mode Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // Updates Toggles & Actions
        binding.switchAutoCheckUpdates.setOnCheckedChangeListener { _, isChecked ->
            preferences.isUpdateAutoCheckEnabled = isChecked
        }
        binding.switchUpdateNotifications.setOnCheckedChangeListener { _, isChecked ->
            preferences.isUpdateNotificationsEnabled = isChecked
        }
        binding.switchWifiOnlyDownload.setOnCheckedChangeListener { _, isChecked ->
            preferences.isUpdateWifiOnly = isChecked
        }

        val checkUpdateAction = {
            binding.pbCheckingUpdates.visibility = View.VISIBLE
            binding.btnCheckUpdatesNow.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val result = com.jarvis.assistant.update.UpdateManager.checkForUpdate(isManual = true)
                binding.pbCheckingUpdates.visibility = View.GONE
                binding.btnCheckUpdatesNow.isEnabled = true
                when (result) {
                    is com.jarvis.assistant.data.update.UpdateCheckResult.UpdateAvailable -> {
                        com.jarvis.assistant.update.UpdateDialogHelper.showUpdatePopup(requireActivity(), result.updateInfo)
                    }
                    is com.jarvis.assistant.data.update.UpdateCheckResult.UpToDate -> {
                        Toast.makeText(requireContext(), "You're up to date! Siya AI v${com.jarvis.assistant.BuildConfig.VERSION_NAME} is the latest version.", Toast.LENGTH_SHORT).show()
                    }
                    is com.jarvis.assistant.data.update.UpdateCheckResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnCheckUpdatesNow.setOnClickListener { checkUpdateAction() }
        binding.rowCheckForUpdates.setOnClickListener { checkUpdateAction() }

        // Section 1: AI & Voice Intelligence
        binding.rowOwnerVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.rowPersonaVoice.setOnClickListener {
            startActivity(Intent(requireContext(), PersonaActivity::class.java))
        }

        binding.rowMicVoiceTest.setOnClickListener {
            startActivity(Intent(requireContext(), MicVoiceTestActivity::class.java))
        }

        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSilentExecutionMode = isChecked
            val msg = if (isChecked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // Section 2: System & Intelligence Data
        binding.rowApiConnection.setOnClickListener {
            startActivity(Intent(requireContext(), ApiConnectionActivity::class.java))
        }

        binding.rowMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowTerminal.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.terminal.TermuxActivity::class.java))
        }

        binding.rowOverlayAvatars.setOnClickListener {
            startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
        }

        binding.rowApplications.setOnClickListener {
            startActivity(Intent(requireContext(), HandsFreeActivity::class.java))
        }

        // Section 3: Appearance & Security
        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyShieldActivity::class.java))
        }

        // Official Links: Telegram, Discussion, WhatsApp & YouTube
        binding.btnOfficialTelegram.setOnClickListener {
            openUrl(BrandConfig.TELEGRAM_CHANNEL_URL)
        }

        binding.btnOfficialDiscussion.setOnClickListener {
            openUrl(BrandConfig.TELEGRAM_DISCUSSION_URL)
        }

        binding.btnOfficialWhatsApp.setOnClickListener {
            openUrl(BrandConfig.WHATSAPP_CHANNEL_URL)
        }

        binding.btnOfficialYoutube.setOnClickListener {
            openUrl(BrandConfig.OFFICIAL_YOUTUBE_URL)
        }

        // Attribution footer opens Telegram channel
        binding.tvFooterCredit.setOnClickListener {
            openUrl(BrandConfig.TELEGRAM_CHANNEL_URL)
        }
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvCurrentLanguage.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWakeWordDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wake Words & Sensitivity")
            .setMessage("• Wake Words: 'Siya', 'Hey Siya'\n• Acoustic Trigger: Double Clap detection\n• Standby Mode: Low-power local Vosk offline engine\n\nWhen standby listening is active, Siya Ai awakens instantly upon hearing your voice.")
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showAppAutomationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Autonomous App Automation")
            .setMessage("Siya Ai is integrated with your device capabilities:\n\n• WhatsApp: Hands-free messaging & calls\n• YouTube: Search and playback automation\n• Phone Controls: Flashlight, Volume, Brightness, Bluetooth\n• Apps & Tools: Launch, search, and manage apps via voice")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showEditNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.userName.ifBlank { BrandConfig.DEFAULT_OPERATOR_NAME })
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Operator Name")
            .setMessage("Enter the name Siya Ai should use to address you:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.userName = newName
                    loadUserData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openUrl(url: String) {
        ExternalLinks.open(requireContext(), url)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
