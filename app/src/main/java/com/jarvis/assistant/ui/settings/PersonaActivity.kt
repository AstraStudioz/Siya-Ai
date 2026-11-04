package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityPersonaBinding
import com.jarvis.assistant.util.BrandConfig
import com.jarvis.assistant.util.ThemeManager

class PersonaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonaBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var currentSelectedPersona = GeminiConstants.PERSONALITY_ASSISTANT

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPersonaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadInitialState()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        binding.tvUnifiedFooter.setOnClickListener {
            com.jarvis.assistant.util.ExternalLinks.open(this, BrandConfig.TELEGRAM_CHANNEL_URL)
        }
    }

    private fun loadInitialState() {
        val current = preferences.personality
        val initialPersona = when (current) {
            GeminiConstants.PERSONALITY_GIRLFRIEND -> GeminiConstants.PERSONALITY_GIRLFRIEND
            GeminiConstants.PERSONALITY_SHIVUOFC -> GeminiConstants.PERSONALITY_SHIVUOFC
            else -> GeminiConstants.PERSONALITY_ASSISTANT
        }
        selectPersona(initialPersona, updateVoiceDefault = false)

        binding.etUserName.setText(preferences.userName.ifBlank { BrandConfig.DEFAULT_OPERATOR_NAME })

        selectVoice(preferences.voice)
        updatePersonaPreview()
        binding.scrollPersona.post { binding.scrollPersona.scrollTo(0, 0) }
    }

    private fun setupListeners() {
        binding.etUserName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePersonaPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.cardPersonaJarvis.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_ASSISTANT, updateVoiceDefault = true)
        }

        binding.cardPersonaGirlfriend.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_GIRLFRIEND, updateVoiceDefault = true)
        }

        binding.cardPersonaShivuOfc.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_SHIVUOFC, updateVoiceDefault = true)
        }

        binding.btnActivatePersona.setOnClickListener {
            saveAndActivate()
        }
    }

    private fun selectPersona(personaKey: String, updateVoiceDefault: Boolean) {
        currentSelectedPersona = personaKey

        binding.rbSelectJarvis.isChecked = (personaKey == GeminiConstants.PERSONALITY_ASSISTANT)
        binding.rbSelectGirlfriend.isChecked = (personaKey == GeminiConstants.PERSONALITY_GIRLFRIEND)
        binding.rbSelectShivuOfc.isChecked = (personaKey == GeminiConstants.PERSONALITY_SHIVUOFC)

        if (updateVoiceDefault) {
            when (personaKey) {
                GeminiConstants.PERSONALITY_GIRLFRIEND -> selectVoice("Aoede")
                GeminiConstants.PERSONALITY_SHIVUOFC -> selectVoice("Kore")
                else -> selectVoice("Aoede")
            }
        }
        if (::binding.isInitialized) updatePersonaPreview()
    }

    private fun updatePersonaPreview() {
        if (!::binding.isInitialized) return
        val persona = when (currentSelectedPersona) {
            GeminiConstants.PERSONALITY_GIRLFRIEND -> "Pari"
            GeminiConstants.PERSONALITY_SHIVUOFC -> BrandConfig.DEVELOPER_NAME
            else -> "Siya Pro"
        }
        val operator = binding.etUserName.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: BrandConfig.DEFAULT_OPERATOR_NAME
        binding.tvPersonaPreview.text = "$persona · ${getSelectedVoice()}"
        binding.tvPersonaGreeting.text = "Ready to help, $operator."
    }

    private fun selectVoice(voiceName: String) {
        when (voiceName.lowercase()) {
            "aoede" -> binding.rbVoiceAoede.isChecked = true
            "kore" -> binding.rbVoiceKore.isChecked = true
            "leda" -> binding.rbVoiceLeda.isChecked = true
            "zephyr" -> binding.rbVoiceZephyr.isChecked = true
            else -> binding.rbVoiceAoede.isChecked = true
        }
    }

    private fun getSelectedVoice(): String {
        return when {
            binding.rbVoiceAoede.isChecked -> "Aoede"
            binding.rbVoiceKore.isChecked -> "Kore"
            binding.rbVoiceLeda.isChecked -> "Leda"
            binding.rbVoiceZephyr.isChecked -> "Zephyr"
            else -> "Aoede"
        }
    }

    private fun saveAndActivate() {
        val userName = binding.etUserName.text?.toString()?.trim()
        if (!userName.isNullOrBlank()) {
            preferences.userName = userName
        } else {
            preferences.userName = BrandConfig.DEFAULT_OPERATOR_NAME
        }

        val voice = getSelectedVoice()
        preferences.voice = voice
        preferences.personality = currentSelectedPersona

        val personaDisplayName = when (currentSelectedPersona) {
            GeminiConstants.PERSONALITY_GIRLFRIEND -> "Pari (Girlfriend Mode)"
            GeminiConstants.PERSONALITY_SHIVUOFC -> "ShivuOfc Neural Core"
            else -> "Siya Pro"
        }

        Toast.makeText(
            this,
            "Activated: $personaDisplayName with $voice Voice",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }
}
