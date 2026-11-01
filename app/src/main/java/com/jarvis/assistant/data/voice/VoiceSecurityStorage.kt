package com.jarvis.assistant.data.voice

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.audio.OwnerVoiceManager
import org.json.JSONArray
import java.util.LinkedList

/**
 * Encrypted on-device storage for biometric voiceprints, calibrated thresholds,
 * adaptive samples, and verification history.
 * Backed by Android Keystore using AES-256-GCM / AES-256-SIV.
 */
class VoiceSecurityStorage(private val context: Context, private val plainPrefs: SharedPreferences) {

    companion object {
        private const val TAG = "VoiceSecurityStorage"
        const val PREF_NAME_SECURE = "siya_voice_secure"

        private const val KEY_ENROLLED_SAMPLES = "key_enrolled_samples"
        private const val KEY_ADAPTIVE_SAMPLES = "key_adaptive_samples"
        private const val KEY_MASTER_CENTROID = "key_master_centroid"
        private const val KEY_CALIBRATED_THRESHOLD = "key_calibrated_threshold"
        private const val KEY_CONSENT_TIMESTAMP = "key_consent_timestamp"
        private const val KEY_TEST_HISTORY = "key_test_history"
        private const val KEY_IS_ADAPTATION_ENABLED = "key_is_adaptation_enabled"
        private const val KEY_SAVE_GUEST_SPEECH = "key_save_guest_speech"
        private const val KEY_MIGRATION_COMPLETED = "key_voice_storage_migrated"

        // Legacy keys in plain prefs
        private const val LEGACY_KEY_PROFILE = "key_owner_voice_profile"
        private const val LEGACY_KEY_SAMPLES = "key_owner_voice_samples"
        private const val LEGACY_KEY_SAMPLE_COUNT = "key_owner_voice_sample_count"
    }

    data class VoiceTestEntry(
        val timestamp: Long,
        val distance: Float,
        val verdict: String
    )

    private val gson = Gson()

    val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREF_NAME_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences init failed; falling back to private prefs: ${e.message}")
            context.getSharedPreferences(PREF_NAME_SECURE + "_fallback", Context.MODE_PRIVATE)
        }
    }

    init {
        performOneTimeMigration()
    }

    /**
     * One-time migration: reads old plain keys, writes encrypted, and deletes plain copies.
     */
    fun performOneTimeMigration() {
        val alreadyMigrated = securePrefs.getBoolean(KEY_MIGRATION_COMPLETED, false)
        if (alreadyMigrated) return

        try {
            val legacyProfile = plainPrefs.getString(LEGACY_KEY_PROFILE, null)
            val legacySamples = plainPrefs.getString(LEGACY_KEY_SAMPLES, null)

            val editor = securePrefs.edit()
            if (!legacyProfile.isNullOrBlank()) {
                editor.putString(KEY_MASTER_CENTROID, legacyProfile)
            }
            if (!legacySamples.isNullOrBlank()) {
                editor.putString(KEY_ENROLLED_SAMPLES, legacySamples)
            }

            editor.putBoolean(KEY_MIGRATION_COMPLETED, true)
            editor.apply()

            // Remove unencrypted biometric vectors from plain SharedPreferences
            plainPrefs.edit()
                .remove(LEGACY_KEY_PROFILE)
                .remove(LEGACY_KEY_SAMPLES)
                .remove(LEGACY_KEY_SAMPLE_COUNT)
                .apply()

            Log.i(TAG, "Voiceprint migration to encrypted storage completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Voiceprint migration failed: ${e.message}", e)
        }
    }

    // --- Biometric Consent ---

    var consentTimestamp: Long
        get() = securePrefs.getLong(KEY_CONSENT_TIMESTAMP, 0L)
        set(value) = securePrefs.edit().putLong(KEY_CONSENT_TIMESTAMP, value).apply()

    val hasGivenConsent: Boolean
        get() = consentTimestamp > 0L

    // --- Profile & Samples ---

    fun getMasterCentroid(): FloatArray? {
        val json = securePrefs.getString(KEY_MASTER_CENTROID, null) ?: return null
        return try {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (_: Exception) {
            null
        }
    }

    fun getEnrolledSamples(): List<FloatArray> {
        val json = securePrefs.getString(KEY_ENROLLED_SAMPLES, null) ?: return emptyList()
        return try {
            val root = JSONArray(json)
            val list = mutableListOf<FloatArray>()
            for (i in 0 until root.length()) {
                val arr = root.getJSONArray(i)
                val vec = FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
                if (vec.isNotEmpty()) list.add(vec)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getAdaptiveSamples(): List<FloatArray> {
        val json = securePrefs.getString(KEY_ADAPTIVE_SAMPLES, null) ?: return emptyList()
        return try {
            val root = JSONArray(json)
            val list = mutableListOf<FloatArray>()
            for (i in 0 until root.length()) {
                val arr = root.getJSONArray(i)
                val vec = FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
                if (vec.isNotEmpty()) list.add(vec)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun hasEnrolledProfile(): Boolean {
        val centroid = getMasterCentroid()
        return centroid != null && centroid.isNotEmpty()
    }

    fun saveEnrolledProfile(
        samples: List<FloatArray>,
        calibratedThreshold: Float
    ) {
        val centroid = OwnerVoiceManager.averageVectors(samples) ?: FloatArray(0)
        val centroidJson = JSONArray().apply {
            for (v in centroid) put(v.toDouble())
        }.toString()

        val samplesJson = JSONArray().apply {
            for (vec in samples) {
                val arr = JSONArray()
                for (v in vec) arr.put(v.toDouble())
                put(arr)
            }
        }.toString()

        securePrefs.edit()
            .putString(KEY_MASTER_CENTROID, centroidJson)
            .putString(KEY_ENROLLED_SAMPLES, samplesJson)
            .remove(KEY_ADAPTIVE_SAMPLES) // clear old adaptive samples on re-enrollment
            .putFloat(KEY_CALIBRATED_THRESHOLD, calibratedThreshold)
            .apply()

        Log.i(TAG, "Saved new enrolled voice profile with ${samples.size} samples, calibrated T=$calibratedThreshold")
    }

    // --- Calibrated Threshold ---

    var calibratedThreshold: Float
        get() = securePrefs.getFloat(KEY_CALIBRATED_THRESHOLD, OwnerVoiceManager.DEFAULT_THRESHOLD)
        set(value) = securePrefs.edit().putFloat(KEY_CALIBRATED_THRESHOLD, value).apply()

    // --- Adaptive Profile Ring Buffer (Max 20) ---

    var isAdaptationEnabled: Boolean
        get() = securePrefs.getBoolean(KEY_IS_ADAPTATION_ENABLED, true)
        set(value) = securePrefs.edit().putBoolean(KEY_IS_ADAPTATION_ENABLED, value).apply()

    @Synchronized
    fun addAdaptiveSample(vector: FloatArray) {
        if (!isAdaptationEnabled) return
        val currentAdaptive = LinkedList(getAdaptiveSamples())

        // Ring buffer max 20 samples
        while (currentAdaptive.size >= 20) {
            currentAdaptive.pollFirst()
        }
        currentAdaptive.add(vector)

        val adaptiveJson = JSONArray().apply {
            for (vec in currentAdaptive) {
                val arr = JSONArray()
                for (v in vec) arr.put(v.toDouble())
                put(arr)
            }
        }.toString()

        // Recompute master centroid including enrolled + adaptive samples
        val enrolled = getEnrolledSamples()
        val allVectors = enrolled + currentAdaptive
        val newCentroid = OwnerVoiceManager.averageVectors(allVectors)

        val editor = securePrefs.edit().putString(KEY_ADAPTIVE_SAMPLES, adaptiveJson)
        if (newCentroid != null) {
            val centroidJson = JSONArray().apply {
                for (v in newCentroid) put(v.toDouble())
            }.toString()
            editor.putString(KEY_MASTER_CENTROID, centroidJson)
        }
        editor.apply()
        Log.i(TAG, "Added adaptive sample (count=${currentAdaptive.size}), centroid updated.")
    }

    fun resetAdaptation() {
        val enrolled = getEnrolledSamples()
        val originalCentroid = OwnerVoiceManager.averageVectors(enrolled)

        val editor = securePrefs.edit().remove(KEY_ADAPTIVE_SAMPLES)
        if (originalCentroid != null) {
            val centroidJson = JSONArray().apply {
                for (v in originalCentroid) put(v.toDouble())
            }.toString()
            editor.putString(KEY_MASTER_CENTROID, centroidJson)
        }
        editor.apply()
        Log.i(TAG, "Adaptive samples reset to original enrolled samples.")
    }

    // --- Save Guest Speech Preference ---

    var saveGuestSpeech: Boolean
        get() = securePrefs.getBoolean(KEY_SAVE_GUEST_SPEECH, false)
        set(value) = securePrefs.edit().putBoolean(KEY_SAVE_GUEST_SPEECH, value).apply()

    // --- Test History (Last 20) ---

    fun getTestHistory(): List<VoiceTestEntry> {
        val json = securePrefs.getString(KEY_TEST_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<VoiceTestEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addTestResult(entry: VoiceTestEntry) {
        val current = LinkedList(getTestHistory())
        while (current.size >= 20) {
            current.pollFirst()
        }
        current.add(entry)
        securePrefs.edit().putString(KEY_TEST_HISTORY, gson.toJson(current)).apply()
    }

    fun clearTestHistory() {
        securePrefs.edit().remove(KEY_TEST_HISTORY).apply()
    }

    fun getRecentRejectionCount(sampleCount: Int = 5): Int {
        val history = getTestHistory().takeLast(sampleCount)
        if (history.isEmpty()) return 0
        return history.count { it.verdict.equals("NOT_OWNER", ignoreCase = true) }
    }

    // --- Wipe Profile ---

    fun clearAllVoiceData() {
        securePrefs.edit()
            .remove(KEY_MASTER_CENTROID)
            .remove(KEY_ENROLLED_SAMPLES)
            .remove(KEY_ADAPTIVE_SAMPLES)
            .remove(KEY_CALIBRATED_THRESHOLD)
            .remove(KEY_CONSENT_TIMESTAMP)
            .remove(KEY_TEST_HISTORY)
            .apply()

        plainPrefs.edit()
            .remove(LEGACY_KEY_PROFILE)
            .remove(LEGACY_KEY_SAMPLES)
            .remove(LEGACY_KEY_SAMPLE_COUNT)
            .putBoolean("key_owner_voice_enabled", false)
            .apply()

        Log.i(TAG, "All owner voice biometric data and test history permanently wiped.")
    }
}
