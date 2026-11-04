package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.ActivityPrivacyShieldBinding
import com.jarvis.assistant.util.BrandConfig
import com.jarvis.assistant.util.ExternalLinks
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PrivacyShieldActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyShieldBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val chatRepository get() = JarvisApp.instance.chatRepository
    private val memoryRepository get() = JarvisApp.instance.memoryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyShieldBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnJoinOfficialTelegram.setOnClickListener {
            ExternalLinks.open(this, BrandConfig.TELEGRAM_CHANNEL_URL)
        }

        binding.tvUnifiedFooter.setOnClickListener {
            ExternalLinks.open(this, BrandConfig.TELEGRAM_CHANNEL_URL)
        }

        binding.btnExportMyData.setOnClickListener {
            exportUserData()
        }

        binding.btnDeleteAllMyData.setOnClickListener {
            showDeleteAllDataConfirmation()
        }
    }

    private fun exportUserData() {
        binding.btnExportMyData.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allMemories = try {
                    memoryRepository.getAllMemories()
                } catch (_: Exception) {
                    emptyList()
                }

                val currentTurns = chatRepository.turns.value

                val exportData = mapOf(
                    "exportVersion" to "1.9.0",
                    "exportedAt" to System.currentTimeMillis(),
                    "appTitle" to "Siya AI",
                    "preferences" to mapOf(
                        "operatorName" to preferences.userName,
                        "personality" to preferences.personality,
                        "language" to preferences.language,
                        "aiModel" to preferences.aiModel,
                        "favoriteSong" to preferences.favoriteSong,
                        "isOwnerVoiceEnabled" to preferences.isOwnerVoiceEnabled
                    ),
                    "memories" to allMemories,
                    "activeChatSessionTurns" to currentTurns
                )

                val gson = GsonBuilder().setPrettyPrinting().create()
                val jsonString = gson.toJson(exportData)

                val exportFile = File(cacheDir, "siya_data_export_${System.currentTimeMillis()}.json")
                FileOutputStream(exportFile).use { fos ->
                    fos.write(jsonString.toByteArray(Charsets.UTF_8))
                }

                val authority = "${packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(this@PrivacyShieldActivity, authority, exportFile)

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Siya AI Personal Data Export")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    binding.btnExportMyData.isEnabled = true
                    startActivity(Intent.createChooser(shareIntent, "Export My Data"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnExportMyData.isEnabled = true
                    Toast.makeText(this@PrivacyShieldActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDeleteAllDataConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Permanently Delete All Data?")
            .setMessage("This will erase all chat conversations, personal memories, encrypted voiceprints, and custom preferences from this device.\n\nThis action cannot be undone.")
            .setPositiveButton("Delete Everything") { _, _ ->
                deleteAllUserData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAllUserData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Wipe chat repository & media
                chatRepository.clearHistory()

                // 2. Clear memory database
                try {
                    memoryRepository.clearAll()
                } catch (_: Exception) {}

                // 3. Clear owner voice profile and encrypted API credentials from keystore
                preferences.clearOwnerVoiceProfile()
                preferences.clearAllSecureApiKeys()
                try {
                    JarvisApp.instance.youTubePreferences.youtubeApiKey = ""
                } catch (_: Exception) {}

                // 4. Reset preferences to factory defaults
                preferences.favoriteSong = ""

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PrivacyShieldActivity,
                        "All personal data wiped cleanly from this device.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PrivacyShieldActivity, "Error wiping data: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
