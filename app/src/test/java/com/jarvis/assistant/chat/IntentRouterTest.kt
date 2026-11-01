package com.jarvis.assistant.chat

import com.jarvis.assistant.network.ImageGenClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {

    @Test
    fun testChatPhrasesStayInChatEvenWhenMentioningTechKeywords() {
        val testCases = listOf(
            "Hello Siya, how are you today?",
            "Tum kaun ho aur kya kar sakti ho?",
            "how does code work in a computer?",
            "what is python programming language?",
            "explain what an API is",
            "code kya hota hai samjhao",
            "difference between compiled and interpreted language"
        )

        for (phrase in testCases) {
            val result = IntentRouter.routeLocal(phrase)
            assertEquals("Expected CHAT for '$phrase' but got ${result.intent}", ChatIntent.CHAT, result.intent)
            assertEquals("siya", result.suggestedMode)
            assertTrue("Expected high confidence for '$phrase'", result.confidence >= 0.80f)
        }
    }

    @Test
    fun testImageGenerationPhrasesRouteToImageMode() {
        val testCases = listOf(
            "sunset ki image banao",
            "generate a wallpaper of snowy mountains",
            "cyberpunk cat ki photo dikhao",
            "ek futuristic car ka logo design karo",
            "draw a cute puppy sticker",
            "tasveer bana do nature ki",
            "create a photo of taj mahal in rain"
        )

        for (phrase in testCases) {
            val result = IntentRouter.routeLocal(phrase)
            assertEquals("Expected IMAGE for '$phrase' but got ${result.intent}", ChatIntent.IMAGE, result.intent)
            assertEquals("image", result.suggestedMode)
            assertTrue("Expected confidence >= 0.70 for '$phrase'", result.confidence >= 0.70f)
        }
    }

    @Test
    fun testImageEditPhrasesRouteToImageEditMode() {
        val testCases = listOf(
            "background badal do blue color me",
            "remove the person from this picture",
            "make this photo look like a cartoon",
            "edit this photo and change colors",
            "hata do background ko"
        )

        for (phrase in testCases) {
            val result = IntentRouter.routeLocal(phrase, hasAttachedImage = true)
            assertEquals("Expected IMAGE_EDIT for '$phrase' but got ${result.intent}", ChatIntent.IMAGE_EDIT, result.intent)
            assertEquals("image", result.suggestedMode)
            assertTrue("Expected confidence >= 0.70 for '$phrase'", result.confidence >= 0.70f)
        }
    }

    @Test
    fun testCodeGenerationPhrasesRouteToCodeMode() {
        val testCases = listOf(
            "html code do portfolio website ka",
            "write a python function to find prime numbers",
            "ek calculator app ka kotlin script likho",
            "fix this bug in javascript function",
            "```val x = 10\nprintln(x)```",
            "write a sql query to select active users",
            "css script likho modern navbar ke liye"
        )

        for (phrase in testCases) {
            val result = IntentRouter.routeLocal(phrase)
            assertEquals("Expected CODE for '$phrase' but got ${result.intent}", ChatIntent.CODE, result.intent)
            assertEquals("code", result.suggestedMode)
            assertTrue("Expected confidence >= 0.70 for '$phrase'", result.confidence >= 0.70f)
        }
    }

    @Test
    fun testImageGenClientPayloadBuilder() {
        val payloadJson = ImageGenClient.buildImagenPayloadJson(
            prompt = "A glowing sapphire crystal",
            aspectRatio = "16:9",
            stylePreset = "Cinematic",
            negativePrompt = "blurry, low quality"
        )

        assertNotNull(payloadJson)
        assertTrue(payloadJson.contains("\"instances\""))
        assertTrue(payloadJson.contains("A glowing sapphire crystal"))
        assertTrue(payloadJson.contains("cinematic atmosphere"))
        assertTrue(payloadJson.contains("\"parameters\""))
        assertTrue(payloadJson.contains("\"sampleCount\":1"))
        assertTrue(payloadJson.contains("\"aspectRatio\":\"16:9\""))
        assertTrue(payloadJson.contains("\"personGeneration\":\"ALLOW_ADULT\""))
        assertTrue(payloadJson.contains("\"outputMimeType\":\"image/jpeg\""))
        assertTrue(payloadJson.contains("\"negativePrompt\":\"blurry, low quality\""))
    }

    @Test
    fun testAutoSwitchUndoAffordanceState() {
        // State simulation:
        var currentMode = "siya"
        var previousMode: String? = null
        var isManuallySelectedMode = false
        var isAutoSwitchEnabled = true

        val inputMessage = "html code do responsive navbar ke liye"

        // Step 1: User types coding message with auto-switch enabled
        val route = IntentRouter.routeLocal(inputMessage, currentMode, hasAttachedImage = false)
        assertTrue(route.confidence >= 0.70f)
        assertEquals("code", route.suggestedMode)

        if (!isManuallySelectedMode && isAutoSwitchEnabled && route.suggestedMode != currentMode) {
            previousMode = currentMode
            currentMode = route.suggestedMode
        }

        assertEquals("code", currentMode)
        assertEquals("siya", previousMode)

        // Step 2: User taps undo button
        val undoTarget = previousMode ?: "siya"
        currentMode = undoTarget
        isManuallySelectedMode = true // Manual lock prevents re-auto-switching

        assertEquals("siya", currentMode)
        assertTrue(isManuallySelectedMode)

        // Step 3: Continued typing or evaluation does not fight manual choice
        val secondCheck = IntentRouter.routeLocal(inputMessage, currentMode, hasAttachedImage = false)
        if (!isManuallySelectedMode && isAutoSwitchEnabled && secondCheck.suggestedMode != currentMode) {
            currentMode = secondCheck.suggestedMode
        }

        // Must remain in "siya" because isManuallySelectedMode is true
        assertEquals("siya", currentMode)
    }
}
