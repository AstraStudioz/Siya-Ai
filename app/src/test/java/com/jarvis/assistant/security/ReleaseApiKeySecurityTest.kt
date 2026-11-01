package com.jarvis.assistant.security

import com.jarvis.assistant.data.model.GeminiConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseApiKeySecurityTest {

    @Test
    fun releaseApiKeyConstantIsEmpty() {
        // Release API key in constants must be empty by default to prevent hardcoded credential leakage
        assertEquals("RELEASE_API_KEY constant must be empty", "", GeminiConstants.RELEASE_API_KEY)
    }

    @Test
    fun appPreferencesDisallowsDebugFallbackInRelease() {
        // Verify via inspection that AppPreferences.kt gates the BuildConfig.GEMINI_API_KEY fallback
        // behind !com.jarvis.assistant.BuildConfig.DEBUG
        val prefsFile = File("src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt")
        }
        assertTrue("AppPreferences.kt must exist", prefsFile.exists())
        val content = prefsFile.readText()

        assertTrue(
            "AppPreferences must check BuildConfig.DEBUG before reading GEMINI_API_KEY",
            content.contains("if (!com.jarvis.assistant.BuildConfig.DEBUG)") ||
                    content.contains("if (!BuildConfig.DEBUG)")
        )
    }

    @Test
    fun noHardcodedKeysInGeminiConstants() {
        val constantsFile = File("src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt")
        }
        assertTrue("GeminiLiveModels.kt must exist", constantsFile.exists())
        val text = constantsFile.readText()
        assertFalse("GeminiLiveModels.kt must not contain AIza keys", text.contains("AIza"))
    }
}
