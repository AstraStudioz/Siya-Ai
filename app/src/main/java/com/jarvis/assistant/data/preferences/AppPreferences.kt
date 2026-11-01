package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.util.BrandConfig

class AppPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "siya_secure_keys_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("AppPreferences", "EncryptedSharedPreferences fallback to default prefs: ${e.message}")
            prefs
        }
    }

    init {
        val hasMigratedTo38 = prefs.getBoolean("key_model_migrated_to_3_8", false)
        if (!hasMigratedTo38) {
            val current = prefs.getString(KEY_MODEL, null)
            if (current == null || current == "models/gemini-3.1-flash-live-preview" || current !in GeminiConstants.SUPPORTED_MODELS) {
                prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
            }
            prefs.edit().putBoolean("key_model_migrated_to_3_8", true).apply()
        } else {
            val current = prefs.getString(KEY_MODEL, null)
            if (current == null || current !in GeminiConstants.SUPPORTED_MODELS) {
                prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
            }
        }

        // Default operator identity: fill in the default when no name was saved, or when the
        // stored value is the old built-in placeholder. Any intentionally saved custom name is kept.
        val storedName = prefs.getString(KEY_USER_NAME, null)
        if (storedName.isNullOrBlank() || storedName == LEGACY_OPERATOR_NAME) {
            prefs.edit().putString(KEY_USER_NAME, BrandConfig.DEFAULT_OPERATOR_NAME).apply()
        }
    }

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val LEGACY_OPERATOR_NAME = "TechGPT X"
        private const val LEGACY_PERSONALITY_NAME = "TechGPT X Mode"
        private const val LEGACY_ASSISTANT_PERSONA = "Jarvis Pro"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_MODEL = "key_model"
        private const val KEY_VOICE = "key_voice"
        private const val KEY_PERSONALITY = "key_personality"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_MIC_MUTED = "key_mic_muted"
        private const val KEY_CHAT_HISTORY = "key_chat_history"
        private const val KEY_FAVORITE_SONG = "key_favorite_song"
        private const val KEY_FAVORITE_APP = "key_favorite_app"
        private const val KEY_SONG_COUNTS = "key_song_counts"
        private const val KEY_USER_BIRTHDAY = "key_user_birthday"
        private const val KEY_USER_LOCATION = "key_user_location"
        private const val KEY_USER_AGE = "key_user_age"
        private const val KEY_DAILY_ROUTINE = "key_daily_routine"
        private const val KEY_OWNER_VOICE_ENABLED = "key_owner_voice_enabled"
        private const val KEY_OWNER_VOICE_PROFILE = "key_owner_voice_profile"
        private const val KEY_OWNER_VOICE_SAMPLES = "key_owner_voice_samples"
        private const val KEY_OWNER_VOICE_THRESHOLD = "key_owner_voice_threshold"
        private const val KEY_OWNER_VOICE_SAMPLE_COUNT = "key_owner_voice_sample_count"
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_SILENT_EXECUTION_MODE = "key_silent_execution_mode"
        private const val KEY_TASK_OVERLAY_ENABLED = "key_task_overlay_enabled"
        private const val KEY_TASK_OVERLAY_AVATAR = "key_task_overlay_avatar"
        private const val KEY_TASK_OVERLAY_MODE = "key_task_overlay_mode"
        private const val KEY_TASK_OVERLAY_EDGE = "key_task_overlay_edge"
        private const val KEY_TASK_OVERLAY_FREE_POSITION = "key_task_overlay_free_position"
        private const val KEY_TASK_OVERLAY_X = "key_task_overlay_x"
        private const val KEY_TASK_OVERLAY_Y = "key_task_overlay_y"
        private const val KEY_APP_THEME = "key_app_theme"
        private const val KEY_CUSTOM_THEME_COLOR = "key_custom_theme_color"
        private const val KEY_ORB_ANIMATION = "key_orb_animation"
        private const val KEY_ORB_SPEED = "key_orb_speed"
        private const val KEY_ORB_GLOW = "key_orb_glow"
        private const val KEY_ORB_SCALE = "key_orb_scale"
        private const val KEY_ORB_HUE = "key_orb_hue"
        private const val KEY_CUSTOM_ENDPOINT = "key_custom_endpoint"
        private const val KEY_CHAT_MODE = "chat_mode"
        private const val KEY_AUTO_SWITCH_CHAT_MODE = "key_auto_switch_chat_mode"
        private const val KEY_IMAGE_ASPECT_RATIO = "key_image_aspect_ratio"
        private const val KEY_IMAGE_STYLE_PRESET = "key_image_style_preset"
        private const val KEY_IMAGE_NEGATIVE_PROMPT = "key_image_negative_prompt"
        private const val KEY_MIC_SENSITIVITY = "key_mic_sensitivity"
        private const val KEY_CLOUDFLARE_ACCOUNT_ID = "key_cf_account_id"
        private const val KEY_CLOUDFLARE_API_TOKEN = "key_cf_api_token"
        private const val KEY_HUGGING_FACE_API_TOKEN = "key_hf_api_token"
        private const val KEY_POLLINATIONS_API_KEY = "key_pollinations_key"
        private const val KEY_PEXELS_API_KEY = "key_pexels_key"
        private const val KEY_PIXABAY_API_KEY = "key_pixabay_key"
        private const val KEY_UNSPLASH_ACCESS_KEY = "key_unsplash_key"
        private const val KEY_GOOGLE_PSE_API_KEY = "key_google_pse_key"
        private const val KEY_GOOGLE_PSE_CX = "key_google_pse_cx"
        private const val KEY_IMAGE_PROVIDER = "key_image_provider"
    }

    var micSensitivity: String
        get() = prefs.getString(KEY_MIC_SENSITIVITY, "normal") ?: "normal"
        set(value) = prefs.edit().putString(KEY_MIC_SENSITIVITY, value).apply()

    var chatMode: String
        get() {
            val mode = prefs.getString(KEY_CHAT_MODE, "siya") ?: "siya"
            return when (mode) {
                "image" -> "image"
                "code" -> "code"
                else -> "siya"
            }
        }
        set(value) {
            val mode = when (value) {
                "image" -> "image"
                "code" -> "code"
                else -> "siya"
            }
            prefs.edit().putString(KEY_CHAT_MODE, mode).apply()
        }

    var isAutoSwitchChatModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SWITCH_CHAT_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SWITCH_CHAT_MODE, value).apply()

    var imageAspectRatio: String
        get() = prefs.getString(KEY_IMAGE_ASPECT_RATIO, "1:1") ?: "1:1"
        set(value) = prefs.edit().putString(KEY_IMAGE_ASPECT_RATIO, value).apply()

    var imageStylePreset: String
        get() = prefs.getString(KEY_IMAGE_STYLE_PRESET, "None") ?: "None"
        set(value) = prefs.edit().putString(KEY_IMAGE_STYLE_PRESET, value).apply()

    var imageNegativePrompt: String
        get() = prefs.getString(KEY_IMAGE_NEGATIVE_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IMAGE_NEGATIVE_PROMPT, value).apply()

    val voiceStorage: com.jarvis.assistant.data.voice.VoiceSecurityStorage by lazy {
        com.jarvis.assistant.data.voice.VoiceSecurityStorage(context, prefs)
    }

    var isOwnerVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_OWNER_VOICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OWNER_VOICE_ENABLED, value).apply()

    var ownerVoiceThresholdMode: String
        get() = prefs.getString("key_owner_voice_threshold_mode", "balanced") ?: "balanced"
        set(value) = prefs.edit().putString("key_owner_voice_threshold_mode", value).apply()

    var ownerVoiceThreshold: Float
        get() {
            val t = voiceStorage.calibratedThreshold
            return when (ownerVoiceThresholdMode) {
                "strict" -> t - 0.06f
                "relaxed" -> t + 0.06f
                else -> t
            }
        }
        set(value) {
            voiceStorage.calibratedThreshold = value
        }

    var isVoiceProfileAdaptationEnabled: Boolean
        get() = voiceStorage.isAdaptationEnabled
        set(value) { voiceStorage.isAdaptationEnabled = value }

    var saveGuestSpeech: Boolean
        get() = voiceStorage.saveGuestSpeech
        set(value) { voiceStorage.saveGuestSpeech = value }

    var ownerVoiceSampleCount: Int
        get() = voiceStorage.getEnrolledSamples().size
        set(_) {}

    fun hasOwnerVoiceProfile(): Boolean = voiceStorage.hasEnrolledProfile()

    fun getOwnerVoiceProfile(): FloatArray? = voiceStorage.getMasterCentroid()

    fun getOwnerVoiceSamples(): List<FloatArray> = voiceStorage.getEnrolledSamples()

    fun getOwnerVoiceAdaptiveSamples(): List<FloatArray> = voiceStorage.getAdaptiveSamples()

    fun saveOwnerVoiceProfile(samples: List<FloatArray>, calibratedThreshold: Float) {
        voiceStorage.saveEnrolledProfile(samples, calibratedThreshold)
    }

    fun saveOwnerVoiceProfile(masterProfile: FloatArray, sampleVectors: List<FloatArray>) {
        val calT = OwnerVoiceManager.calibrateThreshold(sampleVectors)
        voiceStorage.saveEnrolledProfile(sampleVectors, calT)
    }

    fun saveOwnerVoiceProfile(profile: FloatArray, sampleCount: Int) {
        val currentSamples = voiceStorage.getEnrolledSamples()
        voiceStorage.saveEnrolledProfile(currentSamples, OwnerVoiceManager.DEFAULT_THRESHOLD)
    }

    fun clearOwnerVoiceProfile() {
        voiceStorage.clearAllVoiceData()
    }

    fun getSecureString(key: String, default: String = ""): String {
        return try {
            val secure = encryptedPrefs.getString(key, null)?.trim()
            if (!secure.isNullOrBlank()) {
                secure
            } else {
                val plain = prefs.getString(key, "")?.trim() ?: ""
                if (plain.isNotBlank()) {
                    setSecureString(key, plain)
                    plain
                } else {
                    default
                }
            }
        } catch (_: Exception) {
            prefs.getString(key, default)?.trim() ?: default
        }
    }

    fun setSecureString(key: String, value: String) {
        val clean = value.trim()
        try {
            encryptedPrefs.edit().putString(key, clean).apply()
        } catch (_: Exception) {
            prefs.edit().putString(key, clean).apply()
        }
        if (prefs.contains(key)) {
            prefs.edit().remove(key).apply()
        }
    }

    var apiKey: String
        get() {
            val key = getSecureString(KEY_API_KEY)
            if (key.isNotBlank()) return key
            if (!com.jarvis.assistant.BuildConfig.DEBUG) {
                return ""
            }
            return try {
                val clazz = Class.forName("com.jarvis.assistant.BuildConfig")
                val field = clazz.getField("GEMINI_API_KEY")
                (field.get(null) as? String)?.trim() ?: ""
            } catch (e: Throwable) {
                ""
            }
        }
        set(value) = setSecureString(KEY_API_KEY, value)

    var cloudflareAccountId: String
        get() = getSecureString(KEY_CLOUDFLARE_ACCOUNT_ID)
        set(value) = setSecureString(KEY_CLOUDFLARE_ACCOUNT_ID, value)

    var cloudflareApiToken: String
        get() = getSecureString(KEY_CLOUDFLARE_API_TOKEN)
        set(value) = setSecureString(KEY_CLOUDFLARE_API_TOKEN, value)

    var huggingFaceApiToken: String
        get() = getSecureString(KEY_HUGGING_FACE_API_TOKEN)
        set(value) = setSecureString(KEY_HUGGING_FACE_API_TOKEN, value)

    var pollinationsApiKey: String
        get() = getSecureString(KEY_POLLINATIONS_API_KEY)
        set(value) = setSecureString(KEY_POLLINATIONS_API_KEY, value)

    var pexelsApiKey: String
        get() = getSecureString(KEY_PEXELS_API_KEY)
        set(value) = setSecureString(KEY_PEXELS_API_KEY, value)

    var pixabayApiKey: String
        get() = getSecureString(KEY_PIXABAY_API_KEY)
        set(value) = setSecureString(KEY_PIXABAY_API_KEY, value)

    var unsplashAccessKey: String
        get() = getSecureString(KEY_UNSPLASH_ACCESS_KEY)
        set(value) = setSecureString(KEY_UNSPLASH_ACCESS_KEY, value)

    var googlePseApiKey: String
        get() = getSecureString(KEY_GOOGLE_PSE_API_KEY)
        set(value) = setSecureString(KEY_GOOGLE_PSE_API_KEY, value)

    var googlePseCx: String
        get() = getSecureString(KEY_GOOGLE_PSE_CX)
        set(value) = setSecureString(KEY_GOOGLE_PSE_CX, value)

    var imageProvider: String
        get() = prefs.getString(KEY_IMAGE_PROVIDER, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_IMAGE_PROVIDER, value).apply()

    fun clearAllSecureApiKeys() {
        try {
            val secureEditor = encryptedPrefs.edit()
            secureEditor.remove(KEY_API_KEY)
            secureEditor.remove(KEY_CLOUDFLARE_ACCOUNT_ID)
            secureEditor.remove(KEY_CLOUDFLARE_API_TOKEN)
            secureEditor.remove(KEY_HUGGING_FACE_API_TOKEN)
            secureEditor.remove(KEY_POLLINATIONS_API_KEY)
            secureEditor.remove(KEY_PEXELS_API_KEY)
            secureEditor.remove(KEY_PIXABAY_API_KEY)
            secureEditor.remove(KEY_UNSPLASH_ACCESS_KEY)
            secureEditor.remove(KEY_GOOGLE_PSE_API_KEY)
            secureEditor.remove(KEY_GOOGLE_PSE_CX)
            secureEditor.apply()
        } catch (_: Exception) {}

        val plainEditor = prefs.edit()
        plainEditor.remove(KEY_API_KEY)
        plainEditor.remove(KEY_CLOUDFLARE_ACCOUNT_ID)
        plainEditor.remove(KEY_CLOUDFLARE_API_TOKEN)
        plainEditor.remove(KEY_HUGGING_FACE_API_TOKEN)
        plainEditor.remove(KEY_POLLINATIONS_API_KEY)
        plainEditor.remove(KEY_PEXELS_API_KEY)
        plainEditor.remove(KEY_PIXABAY_API_KEY)
        plainEditor.remove(KEY_UNSPLASH_ACCESS_KEY)
        plainEditor.remove(KEY_GOOGLE_PSE_API_KEY)
        plainEditor.remove(KEY_GOOGLE_PSE_CX)
        plainEditor.apply()
    }

    var aiModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL) ?: GeminiConstants.DEFAULT_MODEL
            if (stored !in GeminiConstants.SUPPORTED_MODELS) {
                prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
                return GeminiConstants.DEFAULT_MODEL
            }
            return stored
        }
        set(value) {
            val toSave = if (value in GeminiConstants.SUPPORTED_MODELS) value else GeminiConstants.DEFAULT_MODEL
            prefs.edit().putString(KEY_MODEL, toSave).apply()
        }

    var language: String
        get() {
            val stored = prefs.getString(KEY_LANGUAGE, GeminiConstants.DEFAULT_LANGUAGE) ?: GeminiConstants.DEFAULT_LANGUAGE
            return if (stored.contains("Auto", ignoreCase = true)) "Auto" else stored
        }
        set(value) {
            val clean = if (value.contains("Auto", ignoreCase = true)) "Auto" else value.trim()
            prefs.edit().putString(KEY_LANGUAGE, clean).apply()
        }

    var voice: String
        get() {
            val stored = prefs.getString(KEY_VOICE, GeminiConstants.DEFAULT_VOICE) ?: GeminiConstants.DEFAULT_VOICE
            if (stored !in GeminiConstants.SUPPORTED_VOICES) {
                prefs.edit().putString(KEY_VOICE, GeminiConstants.DEFAULT_VOICE).apply()
                return GeminiConstants.DEFAULT_VOICE
            }
            return stored
        }
        set(value) {
            val valid = if (value in GeminiConstants.SUPPORTED_VOICES) value else GeminiConstants.DEFAULT_VOICE
            prefs.edit().putString(KEY_VOICE, valid).apply()
        }

    var personality: String
        get() {
            val stored = prefs.getString(KEY_PERSONALITY, GeminiConstants.PERSONALITY_ASSISTANT)
                ?: GeminiConstants.PERSONALITY_ASSISTANT
            return when (stored) {
                LEGACY_PERSONALITY_NAME -> GeminiConstants.PERSONALITY_SHIVUOFC
                LEGACY_ASSISTANT_PERSONA, GeminiConstants.PERSONALITY_BOYFRIEND -> GeminiConstants.PERSONALITY_ASSISTANT
                else -> stored
            }
        }
        set(value) = prefs.edit().putString(KEY_PERSONALITY, if (value == GeminiConstants.PERSONALITY_BOYFRIEND) GeminiConstants.PERSONALITY_ASSISTANT else value).apply()

    var userName: String
        get() {
            val name = prefs.getString(KEY_USER_NAME, "") ?: ""
            return if (name.isBlank() || name == "Salaam") BrandConfig.DEFAULT_OPERATOR_NAME else name
        }
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim()).commit()
        }

    var appTheme: String
        get() {
            val stored = prefs.getString(KEY_APP_THEME, "sunset") ?: "sunset"
            return when (stored) {
                "light", "pearl_white" -> "light"
                "dark", "midnight_onyx", "cyberpunk_neon", "emerald_obsidian" -> "dark"
                "red" -> "red"
                "sunset", "rose_gold" -> "sunset"
                "custom" -> "custom"
                else -> "light"
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_THEME, value).apply()

    var customThemeColor: String
        get() = prefs.getString(KEY_CUSTOM_THEME_COLOR, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_CUSTOM_THEME_COLOR, value.trim()).apply()

    var orbAnimation: String
        get() {
            val valid = listOf("holographic_plasma", "cosmic_pulsar", "quantum_core", "cyber_ring", "siri_glow")
            val stored = prefs.getString(KEY_ORB_ANIMATION, "holographic_plasma") ?: "holographic_plasma"
            return if (stored in valid) stored else "holographic_plasma"
        }
        set(value) = prefs.edit().putString(KEY_ORB_ANIMATION, value).apply()

    var orbSpeed: Float
        get() = prefs.getFloat(KEY_ORB_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SPEED, value).apply()

    var orbGlow: Float
        get() = prefs.getFloat(KEY_ORB_GLOW, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_GLOW, value).apply()

    var orbScale: Float
        get() = prefs.getFloat(KEY_ORB_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SCALE, value).apply()

    var orbHue: Float
        get() = prefs.getFloat(KEY_ORB_HUE, -1f)
        set(value) = prefs.edit().putFloat(KEY_ORB_HUE, value).apply()

    var customEndpoint: String
        get() = prefs.getString(KEY_CUSTOM_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_ENDPOINT, value.trim()).apply()

    var userBirthday: String
        get() = prefs.getString(KEY_USER_BIRTHDAY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_BIRTHDAY, value.trim()).commit()
        }

    var userLocation: String
        get() = prefs.getString(KEY_USER_LOCATION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_LOCATION, value.trim()).commit()
        }

    var userAge: String
        get() = prefs.getString(KEY_USER_AGE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_AGE, value.trim()).commit()
        }

    var dailyRoutine: String
        get() = prefs.getString(KEY_DAILY_ROUTINE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DAILY_ROUTINE, value.trim()).commit()
        }

    var favoriteSong: String
        get() = prefs.getString(KEY_FAVORITE_SONG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_SONG, value.trim()).commit()
        }

    var favoriteApp: String
        get() = prefs.getString(KEY_FAVORITE_APP, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_APP, value.trim()).commit()
        }

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    var isSilentExecutionMode: Boolean
        get() = prefs.getBoolean(KEY_SILENT_EXECUTION_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_EXECUTION_MODE, value).apply()

    var isTaskOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_ENABLED, value).apply()

    var taskOverlayAvatar: String
        get() = prefs.getString(KEY_TASK_OVERLAY_AVATAR, "ghost") ?: "ghost"
        set(value) {
            val available = listOf("alien", "blob", "cat", "circle", "cloud", "droid", "drop", "flower", "ghost", "hexagon", "mech", "pebble", "pill", "puddle", "siya", "square", "star", "triangle")
            prefs.edit().putString(KEY_TASK_OVERLAY_AVATAR, value.takeIf { it in available } ?: "ghost").apply()
        }

    /** always keeps the avatar visible while the service is alive; working limits it to active tasks. */
    var taskOverlayMode: String
        get() = prefs.getString(KEY_TASK_OVERLAY_MODE, "always") ?: "always"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_MODE, if (value == "working") "working" else "always").apply()

    /** Preferred dock edge. Dragging the overlay updates this to the closest edge. */
    var taskOverlayEdge: String
        get() = prefs.getString(KEY_TASK_OVERLAY_EDGE, "right") ?: "right"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_EDGE, value.takeIf { it in setOf("left", "right", "top", "bottom") } ?: "right").apply()

    var taskOverlayFreePosition: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_FREE_POSITION, false)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_FREE_POSITION, value).apply()

    var taskOverlayX: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_X, value.coerceAtLeast(0)).apply()

    var taskOverlayY: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_Y, value.coerceAtLeast(0)).apply()

    fun getSongPlayCounts(): MutableMap<String, Int> {
        val json = prefs.getString(KEY_SONG_COUNTS, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Int>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun recordSongPlay(rawSong: String): String {
        val cleanSong = rawSong.trim()
        if (cleanSong.isBlank()) return favoriteSong

        val counts = getSongPlayCounts()
        val currentCount = counts.getOrDefault(cleanSong.lowercase(), 0) + 1
        counts[cleanSong.lowercase()] = currentCount

        // Update stored JSON
        prefs.edit().putString(KEY_SONG_COUNTS, gson.toJson(counts)).apply()

        // Auto-promote to favorite song if played at least 2 times, or if favoriteSong is empty
        val currentFav = favoriteSong
        if (currentFav.isBlank() || currentCount >= 2) {
            // Find most played song
            val topSong = counts.maxByOrNull { it.value }?.key
            if (!topSong.isNullOrBlank()) {
                favoriteSong = cleanSong
            }
        }
        return favoriteSong
    }

    fun loadChatHistory(): List<ChatTurn> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChatTurn>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChatHistory(history: List<ChatTurn>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply()
    }

    fun clearChatHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }
}
