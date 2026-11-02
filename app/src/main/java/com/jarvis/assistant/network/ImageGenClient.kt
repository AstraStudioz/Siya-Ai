package com.jarvis.assistant.network

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.jarvis.assistant.util.AttachmentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class ImageGenResult {
    data class Success(val imageFile: File, val modelUsed: String) : ImageGenResult()
    data class Failure(val reason: String, val canRetry: Boolean = true) : ImageGenResult()
}

class ImageGenClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        const val MODEL_IMAGEN_3 = "imagen-3.0-generate-002"
        const val MODEL_GEMINI_IMAGE = "gemini-2.5-flash-image"
        private const val TAG = "SiyaImageGen"

        val SUPPORTED_ASPECT_RATIOS = listOf("1:1", "16:9", "9:16", "4:3", "3:4")
        val SUPPORTED_STYLE_PRESETS = listOf("None", "Photo", "Cinematic", "Anime", "Digital Art")

        // In-memory cache of validated image models per API key hash to avoid repeated ListModels network calls
        private val cachedImageModels = ConcurrentHashMap<Int, Pair<Long, List<String>>>()
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

        fun applyStylePreset(prompt: String, stylePreset: String): String {
            val trimmedPrompt = prompt.trim()
            return when (stylePreset) {
                "Photo" -> "$trimmedPrompt, photorealistic, 8k resolution, natural lighting, shot on 50mm lens"
                "Cinematic" -> "$trimmedPrompt, cinematic atmosphere, 35mm film photography, dramatic lighting, movie still"
                "Anime" -> "$trimmedPrompt, anime aesthetic, detailed manga illustration, vibrant colors, studio animation style"
                "Digital Art" -> "$trimmedPrompt, digital concept art, highly detailed, trending on ArtStation, vivid composition"
                else -> trimmedPrompt
            }
        }

        fun buildImagenPayloadJson(
            prompt: String,
            aspectRatio: String = "1:1",
            stylePreset: String = "None",
            negativePrompt: String = ""
        ): String {
            val effectivePrompt = applyStylePreset(prompt, stylePreset)
            val effectiveAspectRatio = if (aspectRatio in SUPPORTED_ASPECT_RATIOS) aspectRatio else "1:1"

            val params = mutableMapOf<String, Any>(
                "sampleCount" to 1,
                "aspectRatio" to effectiveAspectRatio,
                "personGeneration" to "ALLOW_ADULT",
                "outputMimeType" to "image/jpeg"
            )
            if (negativePrompt.isNotBlank()) {
                params["negativePrompt"] = negativePrompt.trim()
            }

            val map = mapOf(
                "instances" to listOf(mapOf("prompt" to effectivePrompt)),
                "parameters" to params
            )
            return com.google.gson.Gson().toJson(map)
        }

        fun buildImagenPayload(
            prompt: String,
            aspectRatio: String = "1:1",
            stylePreset: String = "None",
            negativePrompt: String = ""
        ): JSONObject {
            return JSONObject(buildImagenPayloadJson(prompt, aspectRatio, stylePreset, negativePrompt))
        }

        const val PROVIDER_AUTO = "auto"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_POLLINATIONS = "pollinations"
        const val PROVIDER_AI_HORDE = "ai_horde"

        fun getDimensionsForAspectRatio(aspectRatio: String): Pair<Int, Int> {
            return when (aspectRatio) {
                "16:9" -> Pair(1280, 720)
                "9:16" -> Pair(720, 1280)
                "4:3" -> Pair(1024, 768)
                "3:4" -> Pair(768, 1024)
                else -> Pair(1024, 1024)
            }
        }

        fun buildPollinationsUrl(
            prompt: String,
            aspectRatio: String = "1:1",
            stylePreset: String = "None",
            apiKey: String = "",
            seed: Long = 42L
        ): String {
            val effectivePrompt = applyStylePreset(prompt, stylePreset)
            val (w, h) = getDimensionsForAspectRatio(aspectRatio)
            val encodedPrompt = try {
                java.net.URLEncoder.encode(effectivePrompt, "UTF-8").replace("+", "%20")
            } catch (_: Exception) {
                effectivePrompt.replace(" ", "%20")
            }
            val base = "https://image.pollinations.ai/prompt/$encodedPrompt?width=$w&height=$h&nologo=true&seed=$seed"
            return if (apiKey.isNotBlank()) {
                val encodedKey = try {
                    java.net.URLEncoder.encode(apiKey, "UTF-8")
                } catch (_: Exception) {
                    apiKey
                }
                "$base&key=$encodedKey"
            } else {
                base
            }
        }

        fun isQuotaOrPlanError(reason: String): Boolean {
            val r = reason.lowercase()
            return r.contains("429") || r.contains("resource_exhausted") ||
                   r.contains("quota") || r.contains("billing") || r.contains("plan") ||
                   r.contains("credit") || r.contains("limit exceeded") ||
                   r.contains("permissiondenied") || r.contains("permission denied") ||
                   r.contains("permission") || r.contains("paid tier")
        }

        fun buildGeminiImagePayloadJson(prompt: String, stylePreset: String = "None"): String {
            val effectivePrompt = applyStylePreset(prompt, stylePreset)
            val map = mapOf(
                "contents" to listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to "Generate a high-quality visual illustration of: $effectivePrompt")
                        )
                    )
                ),
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("IMAGE")
                )
            )
            return com.google.gson.Gson().toJson(map)
        }

        fun buildAiHordePayloadJson(prompt: String, width: Int, height: Int): String {
            val map = mapOf(
                "prompt" to prompt,
                "params" to mapOf(
                    "n" to 1,
                    "steps" to 20,
                    "width" to width,
                    "height" to height
                )
            )
            return com.google.gson.Gson().toJson(map)
        }
    }

    /**
     * Queries ListModels API to discover and confirm available image-generating models for this API key.
     */
    private suspend fun getOrderedImageModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val keyHash = apiKey.hashCode()
        val now = System.currentTimeMillis()
        cachedImageModels[keyHash]?.let { (timestamp, models) ->
            if (now - timestamp < CACHE_TTL_MS && models.isNotEmpty()) {
                return@withContext models
            }
        }

        val fallbackChain = listOf(MODEL_IMAGEN_3, MODEL_GEMINI_IMAGE)

        try {
            val listUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val request = Request.Builder().url(listUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "ListModels API returned HTTP ${response.code}, using default chain")
                    return@withContext fallbackChain
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val modelsArray = json.optJSONArray("models") ?: return@withContext fallbackChain

                val availableNames = mutableSetOf<String>()
                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.optJSONObject(i) ?: continue
                    val name = m.optString("name", "").removePrefix("models/")
                    availableNames.add(name)
                }

                val discoveredList = mutableListOf<String>()
                if (MODEL_IMAGEN_3 in availableNames) {
                    discoveredList.add(MODEL_IMAGEN_3)
                }
                if (MODEL_GEMINI_IMAGE in availableNames) {
                    discoveredList.add(MODEL_GEMINI_IMAGE)
                }

                val result = if (discoveredList.isNotEmpty()) discoveredList else fallbackChain
                cachedImageModels[keyHash] = Pair(now, result)
                Log.i(TAG, "Validated available image models for key: $result")
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query ListModels: ${e.message}, falling back to default chain")
            fallbackChain
        }
    }

    suspend fun generateImage(
        context: Context,
        apiKey: String = "",
        prompt: String,
        aspectRatio: String = "1:1",
        stylePreset: String = "None",
        negativePrompt: String = "",
        preferredProvider: String = "auto"
    ): ImageGenResult = withContext(Dispatchers.IO) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            return@withContext ImageGenResult.Failure(
                "Please enter an image description.",
                canRetry = false
            )
        }

        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        val effectiveApiKey = apiKey.trim().ifBlank { prefs.apiKey.trim() }

        var lastFailureReason = "Image generation service unavailable"

        // 1. Try Gemini image models (Imagen 3 / Gemini Flash Image) only if key is configured and not quota-blocked
        if (effectiveApiKey.isNotBlank() && !prefs.isGeminiImageQuotaBlocked()) {
            val modelsToTry = getOrderedImageModels(effectiveApiKey)
            for (model in modelsToTry) {
                try {
                    Log.i(TAG, "Attempting image generation with Gemini model: $model")
                    val result = if (model.contains("imagen", ignoreCase = true)) {
                        generateWithImagen(context, effectiveApiKey, model, trimmedPrompt, aspectRatio, stylePreset, negativePrompt)
                    } else {
                        generateWithGeminiContent(context, effectiveApiKey, model, trimmedPrompt, stylePreset)
                    }

                    when (result) {
                        is ImageGenResult.Success -> {
                            Log.i(TAG, "Image generation SUCCESS using model: $model")
                            return@withContext result
                        }
                        is ImageGenResult.Failure -> {
                            Log.w(TAG, "Model $model failed: ${result.reason}. Checking quota/plan error...")
                            lastFailureReason = result.reason
                            if (isQuotaOrPlanError(result.reason)) {
                                Log.w(TAG, "Gemini image quota or plan error detected: remembering for 24 hours.")
                                prefs.recordGeminiImageQuotaError()
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during generation with $model: ${e.message}")
                    lastFailureReason = e.message ?: "Network error"
                    if (isQuotaOrPlanError(lastFailureReason)) {
                        prefs.recordGeminiImageQuotaError()
                        break
                    }
                }
            }
        }

        // 2. Key-Free Fallback 1: Pollinations AI (free, zero setup, no key)
        Log.i(TAG, "Attempting key-free generation with Pollinations AI...")
        val polResult = generateWithPollinations(context, trimmedPrompt, stylePreset, aspectRatio)
        if (polResult is ImageGenResult.Success) {
            return@withContext polResult
        } else if (polResult is ImageGenResult.Failure) {
            lastFailureReason = polResult.reason
        }

        // 3. Key-Free Fallback 2: AI Horde (free, anonymous key 0000000000)
        Log.i(TAG, "Attempting key-free generation with AI Horde...")
        val hordeResult = generateWithAiHorde(context, trimmedPrompt, stylePreset, aspectRatio)
        if (hordeResult is ImageGenResult.Success) {
            return@withContext hordeResult
        } else if (hordeResult is ImageGenResult.Failure) {
            lastFailureReason = hordeResult.reason
        }

        ImageGenResult.Failure(
            reason = "Could not generate image: $lastFailureReason. Tap to retry.",
            canRetry = true
        )
    }

    private fun generateWithImagen(
        context: Context,
        apiKey: String,
        model: String,
        prompt: String,
        aspectRatio: String,
        stylePreset: String,
        negativePrompt: String
    ): ImageGenResult {
        val payloadJson = buildImagenPayloadJson(prompt, aspectRatio, stylePreset, negativePrompt)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:predict?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = parseErrorReason(response.code, responseBody)
                return ImageGenResult.Failure(reason)
            }

            val json = JSONObject(responseBody)
            val predictions = json.optJSONArray("predictions")
            if (predictions != null && predictions.length() > 0) {
                val first = predictions.getJSONObject(0)
                val b64 = first.optString("bytesBase64Encoded", "")
                if (b64.isNotBlank()) {
                    val imageBytes = Base64.decode(b64, Base64.DEFAULT)
                    val savedFile = saveDecodedImage(context, imageBytes)
                    if (savedFile != null) {
                        return ImageGenResult.Success(savedFile, model)
                    }
                }
            }
            return ImageGenResult.Failure("No image data returned from $model")
        }
    }

    private fun generateWithGeminiContent(
        context: Context,
        apiKey: String,
        model: String,
        prompt: String,
        stylePreset: String
    ): ImageGenResult {
        val payloadJson = buildGeminiImagePayloadJson(prompt, stylePreset)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = parseErrorReason(response.code, responseBody)
                return ImageGenResult.Failure(reason)
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                        val b64 = inlineData?.optString("data", "") ?: ""
                        if (b64.isNotBlank()) {
                            val imageBytes = Base64.decode(b64, Base64.DEFAULT)
                            val savedFile = saveDecodedImage(context, imageBytes)
                            if (savedFile != null) {
                                return ImageGenResult.Success(savedFile, model)
                            }
                        }
                    }
                }
            }
            return ImageGenResult.Failure("No inline image data returned from $model")
        }
    }

    fun generateWithPollinations(
        context: Context,
        prompt: String,
        stylePreset: String = "None",
        aspectRatio: String = "1:1"
    ): ImageGenResult {
        val seed = (System.currentTimeMillis() % 1000000L)
        val url = buildPollinationsUrl(prompt, aspectRatio, stylePreset, "", seed)
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ImageGenResult.Failure("Pollinations service error (${response.code})")
                }
                val body = response.body ?: return ImageGenResult.Failure("Empty response from Pollinations")
                val bytes = body.bytes()
                if (bytes.isNotEmpty()) {
                    val savedFile = saveDecodedImage(context, bytes)
                    if (savedFile != null) {
                        ImageGenResult.Success(savedFile, "pollinations-ai")
                    } else {
                        ImageGenResult.Failure("Could not save image to device storage")
                    }
                } else {
                    ImageGenResult.Failure("Zero bytes received from Pollinations")
                }
            }
        } catch (e: Exception) {
            ImageGenResult.Failure("Pollinations request failed: ${e.message}")
        }
    }

    fun generateWithAiHorde(
        context: Context,
        prompt: String,
        stylePreset: String = "None",
        aspectRatio: String = "1:1"
    ): ImageGenResult {
        val effectivePrompt = applyStylePreset(prompt, stylePreset)
        val (width, height) = when (aspectRatio) {
            "16:9" -> Pair(768, 512)
            "9:16" -> Pair(512, 768)
            "4:3" -> Pair(640, 512)
            "3:4" -> Pair(512, 640)
            else -> Pair(512, 512)
        }
        val requestJson = JSONObject().apply {
            put("prompt", effectivePrompt)
            put("params", JSONObject().apply {
                put("sampler_name", "k_euler")
                put("cfg_scale", 7.5)
                put("steps", 20)
                put("width", width)
                put("height", height)
            })
        }
        val postReq = Request.Builder()
            .url("https://aihorde.net/api/v2/generate/async")
            .addHeader("apikey", "0000000000")
            .addHeader("Client-Agent", "SiyaAi:1.0:alphabits.site")
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            val taskId = client.newCall(postReq).execute().use { resp ->
                if (!resp.isSuccessful) return ImageGenResult.Failure("AI Horde request failed (${resp.code})")
                val obj = JSONObject(resp.body?.string().orEmpty())
                obj.optString("id", "")
            }
            if (taskId.isBlank()) return ImageGenResult.Failure("AI Horde did not return a job ID")

            val checkUrl = "https://aihorde.net/api/v2/generate/status/$taskId"
            var imageUrl = ""
            var base64Data = ""
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 25_000L) {
                try { Thread.sleep(2000L) } catch (_: InterruptedException) { break }
                val checkReq = Request.Builder()
                    .url(checkUrl)
                    .addHeader("Client-Agent", "SiyaAi:1.0:alphabits.site")
                    .get()
                    .build()
                client.newCall(checkReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val statusObj = JSONObject(resp.body?.string().orEmpty())
                        val isDone = statusObj.optBoolean("done", false)
                        val gens = statusObj.optJSONArray("generations")
                        if (gens != null && gens.length() > 0) {
                            val gen = gens.getJSONObject(0)
                            val img = gen.optString("img", "")
                            if (img.startsWith("http")) {
                                imageUrl = img
                            } else if (img.isNotBlank()) {
                                base64Data = img
                            }
                        }
                        if (isDone && (imageUrl.isNotBlank() || base64Data.isNotBlank())) {
                            return@use
                        }
                    }
                }
                if (imageUrl.isNotBlank() || base64Data.isNotBlank()) break
            }

            if (imageUrl.isNotBlank()) {
                val dlFile = downloadAndSaveImage(context, imageUrl, "ai-horde")
                if (dlFile != null) return ImageGenResult.Success(dlFile, "ai-horde")
            } else if (base64Data.isNotBlank()) {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val saved = saveDecodedImage(context, bytes)
                if (saved != null) return ImageGenResult.Success(saved, "ai-horde")
            }
            ImageGenResult.Failure("AI Horde generation timed out or worker unavailable")
        } catch (e: Exception) {
            ImageGenResult.Failure("AI Horde error: ${e.message}")
        }
    }

    private fun downloadAndSaveImage(context: Context, url: String, providerTag: String): File? {
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                saveDecodedImage(context, bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseErrorReason(code: Int, body: String): String {
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val message = error?.optString("message", "") ?: ""
            when {
                code == 400 && message.contains("API_KEY", ignoreCase = true) -> "Invalid Gemini API Key"
                code == 403 || message.contains("quota", ignoreCase = true) -> "API Quota or Plan limitation"
                code == 404 -> "Model not supported in your API key's region"
                code == 429 -> "Rate limit reached. Please wait a moment."
                message.contains("safety", ignoreCase = true) || message.contains("blocked", ignoreCase = true) ->
                    "Prompt blocked by AI safety policy"
                message.isNotBlank() -> message.take(70).trim()
                else -> "Server error ($code)"
            }
        } catch (_: Exception) {
            "Network error ($code)"
        }
    }

    private fun saveDecodedImage(context: Context, bytes: ByteArray): File? {
        return try {
            val mediaDir = AttachmentProcessor.getMediaDir(context)
            val file = File(mediaDir, "siya_img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save generated image file: ${e.message}", e)
            null
        }
    }

    fun saveImageToGallery(context: Context, imageFile: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SiyaAi_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SiyaAi")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

            resolver.openOutputStream(uri)?.use { out ->
                imageFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving image to gallery: ${e.message}", e)
            null
        }
    }

    fun createShareIntent(context: Context, imageFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, imageFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
