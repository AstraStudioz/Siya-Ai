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
        const val PROVIDER_CLOUDFLARE = "cloudflare"
        const val PROVIDER_HUGGINGFACE = "huggingface"
        const val PROVIDER_POLLINATIONS = "pollinations"

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

        fun buildCloudflarePayloadJson(prompt: String, stylePreset: String = "None"): String {
            val effectivePrompt = applyStylePreset(prompt, stylePreset)
            val map = mapOf(
                "prompt" to effectivePrompt,
                "num_steps" to 4
            )
            return com.google.gson.Gson().toJson(map)
        }

        fun buildHuggingFacePayloadJson(prompt: String, stylePreset: String = "None"): String {
            val effectivePrompt = applyStylePreset(prompt, stylePreset)
            val map = mapOf(
                "inputs" to effectivePrompt
            )
            return com.google.gson.Gson().toJson(map)
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
        val providerSetting = if (preferredProvider != "auto") preferredProvider else prefs.imageProvider

        // If specific provider chosen explicitly, execute directly
        if (providerSetting == PROVIDER_POLLINATIONS) {
            val polResult = generateWithPollinations(context, trimmedPrompt, stylePreset, aspectRatio, prefs.pollinationsApiKey)
            if (polResult is ImageGenResult.Success) return@withContext polResult
        } else if (providerSetting == PROVIDER_CLOUDFLARE) {
            val cfResult = generateWithCloudflare(context, prefs.cloudflareAccountId, prefs.cloudflareApiToken, trimmedPrompt, stylePreset, aspectRatio)
            if (cfResult is ImageGenResult.Success) return@withContext cfResult
        } else if (providerSetting == PROVIDER_HUGGINGFACE) {
            val hfResult = generateWithHuggingFace(context, prefs.huggingFaceApiToken, trimmedPrompt, stylePreset, aspectRatio)
            if (hfResult is ImageGenResult.Success) return@withContext hfResult
        }

        var lastFailureReason = "Image generation service unavailable"

        // 1. Try Gemini Imagen 3 / Gemini Flash Image if key is available
        if (effectiveApiKey.isNotBlank()) {
            val modelsToTry = getOrderedImageModels(effectiveApiKey)
            for (model in modelsToTry) {
                try {
                    Log.i(TAG, "Attempting image generation with model: $model")
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
                            Log.w(TAG, "Model $model failed: ${result.reason}. Trying next fallback...")
                            lastFailureReason = result.reason
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during generation with $model: ${e.message}")
                    lastFailureReason = e.message ?: "Network error"
                }
            }
        }

        // 2. Try Cloudflare Workers AI FLUX if configured
        if (prefs.cloudflareAccountId.isNotBlank() && prefs.cloudflareApiToken.isNotBlank()) {
            Log.i(TAG, "Attempting fallback to Cloudflare Workers AI FLUX...")
            val cfResult = generateWithCloudflare(context, prefs.cloudflareAccountId, prefs.cloudflareApiToken, trimmedPrompt, stylePreset, aspectRatio)
            if (cfResult is ImageGenResult.Success) {
                return@withContext cfResult
            } else if (cfResult is ImageGenResult.Failure) {
                lastFailureReason = cfResult.reason
            }
        }

        // 3. Try Hugging Face FLUX if configured
        if (prefs.huggingFaceApiToken.isNotBlank()) {
            Log.i(TAG, "Attempting fallback to Hugging Face FLUX...")
            val hfResult = generateWithHuggingFace(context, prefs.huggingFaceApiToken, trimmedPrompt, stylePreset, aspectRatio)
            if (hfResult is ImageGenResult.Success) {
                return@withContext hfResult
            } else if (hfResult is ImageGenResult.Failure) {
                lastFailureReason = hfResult.reason
            }
        }

        // 4. Fallback to Pollinations AI (free, no setup required)
        Log.i(TAG, "Attempting fallback to Pollinations AI...")
        val polResult = generateWithPollinations(context, trimmedPrompt, stylePreset, aspectRatio, prefs.pollinationsApiKey)
        if (polResult is ImageGenResult.Success) {
            return@withContext polResult
        } else if (polResult is ImageGenResult.Failure) {
            lastFailureReason = polResult.reason
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

    fun generateWithCloudflare(
        context: Context,
        accountId: String,
        apiToken: String,
        prompt: String,
        stylePreset: String = "None",
        aspectRatio: String = "1:1"
    ): ImageGenResult {
        if (accountId.isBlank() || apiToken.isBlank()) {
            return ImageGenResult.Failure("Cloudflare credentials not configured.")
        }
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/@cf/black-forest-labs/flux-1-schnell"
        val payload = buildCloudflarePayloadJson(prompt, stylePreset)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiToken")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = parseErrorReason(response.code, response.body?.string().orEmpty())
                    return ImageGenResult.Failure(err)
                }
                val body = response.body ?: return ImageGenResult.Failure("Empty response from Cloudflare")
                val contentType = response.header("Content-Type", "") ?: ""
                val imageBytes = if (contentType.contains("image")) {
                    body.bytes()
                } else {
                    val str = body.string()
                    val json = JSONObject(str)
                    val resultObj = json.optJSONObject("result")
                    val b64 = resultObj?.optString("image", "") ?: ""
                    if (b64.isNotBlank()) Base64.decode(b64, Base64.DEFAULT) else return ImageGenResult.Failure("No image returned from Cloudflare")
                }
                val savedFile = saveDecodedImage(context, imageBytes)
                if (savedFile != null) {
                    ImageGenResult.Success(savedFile, "cloudflare-flux-schnell")
                } else {
                    ImageGenResult.Failure("Could not save image to device storage")
                }
            }
        } catch (e: Exception) {
            ImageGenResult.Failure("Cloudflare request failed: ${e.message}")
        }
    }

    fun generateWithHuggingFace(
        context: Context,
        apiToken: String,
        prompt: String,
        stylePreset: String = "None",
        aspectRatio: String = "1:1"
    ): ImageGenResult {
        if (apiToken.isBlank()) {
            return ImageGenResult.Failure("Hugging Face API token not configured.")
        }
        val url = "https://api-inference.huggingface.co/models/black-forest-labs/FLUX.1-schnell"
        val payload = buildHuggingFacePayloadJson(prompt, stylePreset)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiToken")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = parseErrorReason(response.code, response.body?.string().orEmpty())
                    return ImageGenResult.Failure(err)
                }
                val body = response.body ?: return ImageGenResult.Failure("Empty response from Hugging Face")
                val imageBytes = body.bytes()
                if (imageBytes.isNotEmpty()) {
                    val savedFile = saveDecodedImage(context, imageBytes)
                    if (savedFile != null) {
                        ImageGenResult.Success(savedFile, "huggingface-flux-schnell")
                    } else {
                        ImageGenResult.Failure("Could not save image to device storage")
                    }
                } else {
                    ImageGenResult.Failure("Empty image bytes received from Hugging Face")
                }
            }
        } catch (e: Exception) {
            ImageGenResult.Failure("Hugging Face request failed: ${e.message}")
        }
    }

    fun generateWithPollinations(
        context: Context,
        prompt: String,
        stylePreset: String = "None",
        aspectRatio: String = "1:1",
        apiKey: String = ""
    ): ImageGenResult {
        val seed = (System.currentTimeMillis() % 1000000L)
        val url = buildPollinationsUrl(prompt, aspectRatio, stylePreset, apiKey, seed)
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

    suspend fun searchStockPhoto(
        context: Context,
        query: String,
        orientation: String = "squarish"
    ): ImageGenResult = withContext(Dispatchers.IO) {
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext ImageGenResult.Failure("Please enter a search query.")

        // 1. Try Unsplash
        if (prefs.unsplashAccessKey.isNotBlank()) {
            try {
                val url = "https://api.unsplash.com/search/photos?query=${Uri.encode(cleanQuery)}&per_page=1"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Client-ID ${prefs.unsplashAccessKey}")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val first = results.getJSONObject(0)
                            val urls = first.optJSONObject("urls")
                            val imageUrl = urls?.optString("regular", "") ?: ""
                            if (imageUrl.isNotBlank()) {
                                val dlResult = downloadAndSaveImage(context, imageUrl, "unsplash")
                                if (dlResult != null) return@withContext ImageGenResult.Success(dlResult, "unsplash")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Try Pexels
        if (prefs.pexelsApiKey.isNotBlank()) {
            try {
                val url = "https://api.pexels.com/v1/search?query=${Uri.encode(cleanQuery)}&per_page=1"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", prefs.pexelsApiKey)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val photos = json.optJSONArray("photos")
                        if (photos != null && photos.length() > 0) {
                            val first = photos.getJSONObject(0)
                            val src = first.optJSONObject("src")
                            val imageUrl = src?.optString("large", "") ?: ""
                            if (imageUrl.isNotBlank()) {
                                val dlResult = downloadAndSaveImage(context, imageUrl, "pexels")
                                if (dlResult != null) return@withContext ImageGenResult.Success(dlResult, "pexels")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Try Pixabay
        if (prefs.pixabayApiKey.isNotBlank()) {
            try {
                val url = "https://pixabay.com/api/?key=${prefs.pixabayApiKey}&q=${Uri.encode(cleanQuery)}&image_type=photo&per_page=3"
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val hits = json.optJSONArray("hits")
                        if (hits != null && hits.length() > 0) {
                            val first = hits.getJSONObject(0)
                            val imageUrl = first.optString("largeImageURL", "")
                            if (imageUrl.isNotBlank()) {
                                val dlResult = downloadAndSaveImage(context, imageUrl, "pixabay")
                                if (dlResult != null) return@withContext ImageGenResult.Success(dlResult, "pixabay")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Try Google PSE
        if (prefs.googlePseApiKey.isNotBlank() && prefs.googlePseCx.isNotBlank()) {
            try {
                val url = "https://www.googleapis.com/customsearch/v1?key=${prefs.googlePseApiKey}&cx=${prefs.googlePseCx}&q=${Uri.encode(cleanQuery)}&searchType=image&num=1"
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val items = json.optJSONArray("items")
                        if (items != null && items.length() > 0) {
                            val first = items.getJSONObject(0)
                            val link = first.optString("link", "")
                            if (link.isNotBlank()) {
                                val dlResult = downloadAndSaveImage(context, link, "google-pse")
                                if (dlResult != null) return@withContext ImageGenResult.Success(dlResult, "google-pse")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        ImageGenResult.Failure("No stock photos found or search provider not configured.")
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
