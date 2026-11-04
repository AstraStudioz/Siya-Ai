package com.jarvis.assistant

import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.network.ImageGenClient
import com.jarvis.assistant.util.BrandConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase2CleanupTest {

    @Test
    fun testOnlySiyaAvatarAllowedInPreferences() {
        assertEquals(listOf("siya"), AppPreferences.ALLOWED_TASK_OVERLAY_AVATARS)
        assertEquals("siya", AppPreferences.TASK_OVERLAY_DEFAULT_AVATAR)
    }

    @Test
    fun testSingleKeyContract_apiConnectionLayoutHasOnlyGeminiKeyAndNoThirdPartyCredentials() {
        val file = File("src/main/res/layout/activity_api_connection.xml")
        assertTrue("activity_api_connection.xml should exist", file.exists())
        val xml = file.readText()

        // Gemini key exists
        assertTrue("Gemini API key input field must be present", xml.contains("etApiKey"))
        assertTrue("Test Image Generation button must be present", xml.contains("btnTestImageGeneration"))

        // Third-party provider fields removed
        assertFalse("Cloudflare Account ID must be removed", xml.contains("etCloudflareAccountId"))
        assertFalse("Cloudflare API Token must be removed", xml.contains("etCloudflareApiToken"))
        assertFalse("Hugging Face API Token must be removed", xml.contains("etHuggingFaceApiToken"))
        assertFalse("Pollinations API Key must be removed", xml.contains("etPollinationsApiKey"))
        assertFalse("Image Provider RadioGroup must be removed", xml.contains("rgImageProviderGroup"))

        // Stock photo credentials removed
        assertFalse("Unsplash Access Key must be removed", xml.contains("etUnsplashAccessKey"))
        assertFalse("Pexels API Key must be removed", xml.contains("etPexelsApiKey"))
        assertFalse("Pixabay API Key must be removed", xml.contains("etPixabayApiKey"))
        assertFalse("Google PSE API Key must be removed", xml.contains("etGooglePseApiKey"))
        assertFalse("Google PSE CX must be removed", xml.contains("etGooglePseCx"))
    }

    @Test
    fun testSettingsOrder_autoSwitchChatModeIsLastRowBelowSilentMode() {
        val file = File("src/main/res/layout/fragment_settings.xml")
        assertTrue("fragment_settings.xml should exist", file.exists())
        val xml = file.readText()

        val silentIndex = xml.indexOf("rowSilentMode")
        val autoSwitchIndex = xml.indexOf("rowAutoSwitchChatMode")

        assertTrue("rowSilentMode must be present in settings layout", silentIndex > 0)
        assertTrue("rowAutoSwitchChatMode must be present in settings layout", autoSwitchIndex > 0)
        assertTrue(
            "Auto-switch chat mode must be the LAST row in Voice & Neural card, below Silent Execution Mode",
            autoSwitchIndex > silentIndex
        )
    }

    @Test
    fun testOverlayAvatars_onlySiyaPreviewAndNoGalleryGrid() {
        val file = File("src/main/res/layout/activity_overlay_avatar.xml")
        assertTrue("activity_overlay_avatar.xml should exist", file.exists())
        val xml = file.readText()

        assertTrue("Single avatarPreview WebView must be present", xml.contains("avatarPreview"))
        assertTrue(
            "Avatar subtitle must describe Siya default neural avatar",
            xml.contains("Siya's default neural avatar; responsive to voice and emotion")
        )
        assertFalse("Multi-avatar gallery grid must be removed", xml.contains("avatarGallery"))
        assertFalse("Gallery selection instructions must be removed", xml.contains("CHOOSE AN AVATAR"))
    }

    @Test
    fun testOfficialLinksAndUnifiedFooterConstants() {
        assertEquals("https://t.me/AstraStudiosPvt", BrandConfig.OFFICIAL_WEBSITE_URL)
        assertEquals("https://youtube.com/@astrastudiospvt?si=7rjDrOpnLr-Ch1ws", BrandConfig.OFFICIAL_YOUTUBE_URL)
        assertEquals("https://t.me/AstraStudiosPvt", BrandConfig.TELEGRAM_CHANNEL_URL)
        assertEquals("https://t.me/StudioxDiscussion", BrandConfig.TELEGRAM_DISCUSSION_URL)
        assertEquals("https://whatsapp.com/channel/0029VbE9XJN5EjxqdhXWXQ0f", BrandConfig.WHATSAPP_CHANNEL_URL)
        assertEquals("Astra Studios", BrandConfig.CRAFTED_BY)
        assertEquals("ShivuOfc", BrandConfig.DEVELOPER_NAME)
        assertEquals("Siya Ai • Powered by Astra Studios", BrandConfig.UNIFIED_FOOTER_TEXT)
    }

    @Test
    fun testUnifiedFooterPresentAcrossAllScreens() {
        val targetLayouts = listOf(
            "activity_api_connection.xml",
            "fragment_settings.xml",
            "activity_overlay_avatar.xml",
            "activity_permission.xml",
            "activity_hands_free.xml",
            "activity_persona.xml",
            "activity_voice_enrollment.xml",
            "activity_mic_voice_test.xml",
            "activity_privacy_shield.xml"
        )

        for (name in targetLayouts) {
            val file = File("src/main/res/layout/$name")
            assertTrue("Layout $name must exist", file.exists())
            val content = file.readText()
            assertTrue(
                "Layout $name must contain unified footer: '${BrandConfig.UNIFIED_FOOTER_TEXT}'",
                content.contains(BrandConfig.UNIFIED_FOOTER_TEXT)
            )
        }
    }

    @Test
    fun testPromptGenerator_brandingReferences() {
        val file = File("src/main/java/com/jarvis/assistant/util/PromptGenerator.kt")
        assertTrue("PromptGenerator.kt must exist", file.exists())
        val content = file.readText()
        assertTrue("PromptGenerator.kt must contain creator name 'ShivuOfc'", content.contains("ShivuOfc"))
        assertTrue("PromptGenerator.kt must reference Astra Studios", content.contains("Astra Studios"))
        assertFalse("PromptGenerator.kt must not contain old creator name 'Alpha Bits'", content.contains("Alpha Bits"))
    }

    @Test
    fun testKeyFreeImageGenerationCascade() {
        assertEquals("auto", ImageGenClient.PROVIDER_AUTO)
        assertEquals("gemini", ImageGenClient.PROVIDER_GEMINI)
        assertEquals("pollinations", ImageGenClient.PROVIDER_POLLINATIONS)
        assertEquals("ai_horde", ImageGenClient.PROVIDER_AI_HORDE)

        // Quota/plan errors trigger 24h fallback
        assertTrue(ImageGenClient.isQuotaOrPlanError("429 RESOURCE_EXHAUSTED"))
        assertTrue(ImageGenClient.isQuotaOrPlanError("Quota exceeded for project"))
        assertTrue(ImageGenClient.isQuotaOrPlanError("PermissionDenied"))
        assertTrue(ImageGenClient.isQuotaOrPlanError("billing not enabled"))
        assertFalse(ImageGenClient.isQuotaOrPlanError("SocketTimeoutException"))
    }
}
