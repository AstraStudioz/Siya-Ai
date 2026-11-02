package com.jarvis.assistant.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenClientTest {

    @Test
    fun testModelConstants() {
        assertEquals("imagen-3.0-generate-002", ImageGenClient.MODEL_IMAGEN_3)
        assertEquals("gemini-2.5-flash-image", ImageGenClient.MODEL_GEMINI_IMAGE)
    }

    @Test
    fun testStylePresets() {
        val basePrompt = "Tiger in dense jungle"
        val photoPrompt = ImageGenClient.applyStylePreset(basePrompt, "Photo")
        assertTrue(photoPrompt.contains("photorealistic"))

        val animePrompt = ImageGenClient.applyStylePreset(basePrompt, "Anime")
        assertTrue(animePrompt.contains("anime aesthetic"))

        val cinematicPrompt = ImageGenClient.applyStylePreset(basePrompt, "Cinematic")
        assertTrue(cinematicPrompt.contains("cinematic atmosphere"))

        val nonePrompt = ImageGenClient.applyStylePreset(basePrompt, "None")
        assertEquals(basePrompt, nonePrompt)
    }

    @Test
    fun testImagenPayload() {
        val jsonStr = ImageGenClient.buildImagenPayloadJson(
            prompt = "Tiger ki image",
            aspectRatio = "1:1",
            stylePreset = "None",
            negativePrompt = ""
        )
        assertNotNull(jsonStr)
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val instances = json.getAsJsonArray("instances")
        assertEquals(1, instances.size())
        assertEquals("Tiger ki image", instances[0].asJsonObject.get("prompt").asString)

        val params = json.getAsJsonObject("parameters")
        assertEquals(1, params.get("sampleCount").asInt)
        assertEquals("1:1", params.get("aspectRatio").asString)
        assertEquals("image/jpeg", params.get("outputMimeType").asString)
        assertFalse(params.has("negativePrompt"))
    }

    @Test
    fun testGeminiImagePayload() {
        val jsonStr = ImageGenClient.buildGeminiImagePayloadJson("A soaring eagle", "Cinematic")
        assertNotNull(jsonStr)
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val contents = json.getAsJsonArray("contents")
        assertEquals(1, contents.size())

        val textPart = contents[0].asJsonObject.getAsJsonArray("parts")[0].asJsonObject.get("text").asString
        assertTrue(textPart.contains("A soaring eagle"))
        assertTrue(textPart.contains("cinematic atmosphere"))

        val genConfig = json.getAsJsonObject("generationConfig")
        val modalities = genConfig.getAsJsonArray("responseModalities")
        assertEquals(1, modalities.size())
        assertEquals("IMAGE", modalities[0].asString)
    }

    @Test
    fun testProviderConstants() {
        assertEquals("auto", ImageGenClient.PROVIDER_AUTO)
        assertEquals("gemini", ImageGenClient.PROVIDER_GEMINI)
        assertEquals("pollinations", ImageGenClient.PROVIDER_POLLINATIONS)
        assertEquals("ai_horde", ImageGenClient.PROVIDER_AI_HORDE)
    }

    @Test
    fun testAspectRatioDimensions() {
        assertEquals(Pair(1024, 1024), ImageGenClient.getDimensionsForAspectRatio("1:1"))
        assertEquals(Pair(1280, 720), ImageGenClient.getDimensionsForAspectRatio("16:9"))
        assertEquals(Pair(720, 1280), ImageGenClient.getDimensionsForAspectRatio("9:16"))
        assertEquals(Pair(1024, 768), ImageGenClient.getDimensionsForAspectRatio("4:3"))
        assertEquals(Pair(768, 1024), ImageGenClient.getDimensionsForAspectRatio("3:4"))
        assertEquals(Pair(1024, 1024), ImageGenClient.getDimensionsForAspectRatio("unknown"))
    }

    @Test
    fun testPollinationsUrlBuilder() {
        val url = ImageGenClient.buildPollinationsUrl(
            prompt = "A glowing neon cyberpunk cat",
            aspectRatio = "16:9",
            stylePreset = "Anime",
            apiKey = "",
            seed = 9999L
        )
        assertNotNull(url)
        assertTrue(url.startsWith("https://image.pollinations.ai/prompt/"))
        assertTrue(url.contains("width=1280"))
        assertTrue(url.contains("height=720"))
        assertTrue(url.contains("nologo=true"))
        assertTrue(url.contains("seed=9999"))
        assertTrue(url.contains("anime%20aesthetic"))
    }

    @Test
    fun testQuotaOrPlanErrorDetection() {
        assertTrue(ImageGenClient.isQuotaOrPlanError("RESOURCE_EXHAUSTED: Quota exceeded for project"))
        assertTrue(ImageGenClient.isQuotaOrPlanError("HTTP 429: Rate limit exceeded"))
        assertTrue(ImageGenClient.isQuotaOrPlanError("PermissionDenied: The caller does not have permission"))
        assertTrue(ImageGenClient.isQuotaOrPlanError("Imagen 3 is only available for paid tier accounts"))
        assertTrue(ImageGenClient.isQuotaOrPlanError("billing not enabled"))
        assertFalse(ImageGenClient.isQuotaOrPlanError("Network connection reset"))
        assertFalse(ImageGenClient.isQuotaOrPlanError("SocketTimeoutException"))
    }

    @Test
    fun testAiHordePayloadBuilder() {
        val payload = ImageGenClient.buildAiHordePayloadJson("Futuristic skyline", 1024, 1024)
        assertNotNull(payload)
        val json = JsonParser.parseString(payload).asJsonObject
        assertEquals("Futuristic skyline", json.get("prompt").asString)
        val params = json.getAsJsonObject("params")
        assertEquals(1024, params.get("width").asInt)
        assertEquals(1024, params.get("height").asInt)
        assertEquals(1, params.get("n").asInt)
    }
}
