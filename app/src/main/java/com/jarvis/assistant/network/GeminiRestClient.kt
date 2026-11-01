package com.jarvis.assistant.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.util.TimeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class RestInlineData(
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("data") val data: String
)

data class RestFunctionCall(
    @SerializedName("name") val name: String,
    @SerializedName("args") val args: Map<String, Any?>? = null
)

data class RestPart(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: RestInlineData? = null,
    @SerializedName("functionCall") val functionCall: RestFunctionCall? = null,
    @SerializedName("thought") val thought: Boolean? = null
)

data class RestContent(
    @SerializedName("role") val role: String,
    @SerializedName("parts") val parts: List<RestPart>
)

data class RestSystemInstruction(
    @SerializedName("parts") val parts: List<RestPart>
)

data class RestFunctionDeclaration(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: Map<String, Any?>? = null
)

data class RestTool(
    @SerializedName("functionDeclarations") val functionDeclarations: List<RestFunctionDeclaration>
)

data class RestGenerationConfig(
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("responseModalities") val responseModalities: List<String>? = null,
    @SerializedName("maxOutputTokens") val maxOutputTokens: Int? = null,
    @SerializedName("imageConfig") val imageConfig: Map<String, Any?>? = null
)

data class RestRequestBody(
    @SerializedName("systemInstruction") val systemInstruction: RestSystemInstruction? = null,
    @SerializedName("contents") val contents: List<RestContent>,
    @SerializedName("generationConfig") val generationConfig: RestGenerationConfig? = null,
    @SerializedName("tools") val tools: List<RestTool>? = null
)

data class RestPromptFeedback(
    @SerializedName("blockReason") val blockReason: String? = null
)

data class RestCandidateContent(
    @SerializedName("role") val role: String? = null,
    @SerializedName("parts") val parts: List<RestPart>? = null
)

data class RestCandidate(
    @SerializedName("content") val content: RestCandidateContent? = null,
    @SerializedName("finishReason") val finishReason: String? = null
)

data class RestError(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("status") val status: String? = null
)

data class RestResponseBody(
    @SerializedName("candidates") val candidates: List<RestCandidate>? = null,
    @SerializedName("promptFeedback") val promptFeedback: RestPromptFeedback? = null,
    @SerializedName("error") val error: RestError? = null
)

data class GeminiRestResult(
    val text: String?,
    val inlineImages: List<RestInlineData> = emptyList(),
    val functionCalls: List<RestFunctionCall> = emptyList(),
    val modelUsed: String
)

data class GeminiStreamResult(
    val text: String,
    val modelUsed: String,
    val finishReason: String? = null,
    val isMaxTokens: Boolean = false
)

enum class GeminiErrorKind {
    OVERLOADED,    // 503 / UNAVAILABLE / "high demand"
    RATE_LIMITED,  // 429 / RESOURCE_EXHAUSTED
    TRANSIENT,     // 500 / 502 / 504 / IOException
    TIMEOUT,       // SocketTimeoutException / idle timeout / no first token in 20s
    MODEL_GONE,    // 404 / NOT_FOUND
    AUTH,          // 401 / 403 / API_KEY_INVALID
    BAD_REQUEST    // 400 / INVALID_ARGUMENT / SAFETY block
}

class GeminiApiException(
    val kind: GeminiErrorKind,
    val friendlyMessage: String,
    cause: Throwable? = null
) : IOException(friendlyMessage, cause)

class GeminiRestClient(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val streamingClient: OkHttpClient = defaultStreamingClient,
    private val imageHttpClient: OkHttpClient = defaultImageHttpClient,
    private val gson: Gson = defaultGson
) {

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultGson: Gson = GsonBuilder().create()

        val defaultHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val defaultStreamingClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // Acts as idle timeout during SSE
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val defaultImageHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        @Volatile
        var lastWorkingModel: String? = null
        @Volatile
        var lastWorkingModelTimestamp: Long = 0L

        fun getOrderedModels(configuredModels: List<String>): List<String> {
            val now = System.currentTimeMillis()
            val cached = lastWorkingModel
            val result = mutableListOf<String>()
            if (cached != null && (now - lastWorkingModelTimestamp) < 10 * 60 * 1000L && configuredModels.contains(cached)) {
                result.add(cached)
            }
            for (m in configuredModels) {
                if (!result.contains(m)) {
                    result.add(m)
                }
            }
            return result
        }

        fun recordWorkingModel(model: String) {
            lastWorkingModel = model
            lastWorkingModelTimestamp = System.currentTimeMillis()
        }

        fun classifyError(code: Int?, message: String?, cause: Throwable?): GeminiErrorKind {
            if (cause is SocketTimeoutException) {
                return GeminiErrorKind.TIMEOUT
            }
            val msg = (message ?: cause?.message ?: "").lowercase()
            if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("time out") || msg.contains("idle timeout") || msg.contains("first token")) {
                return GeminiErrorKind.TIMEOUT
            }
            if (code == 404 || msg.contains("404") || msg.contains("not found") || msg.contains("model_gone")) {
                return GeminiErrorKind.MODEL_GONE
            }
            if (code == 401 || code == 403 || msg.contains("401") || msg.contains("403") || msg.contains("api_key_invalid") || msg.contains("permission_denied") || msg.contains("api key")) {
                return GeminiErrorKind.AUTH
            }
            if (code == 429 || msg.contains("429") || msg.contains("resource_exhausted") || msg.contains("quota") || msg.contains("rate limit")) {
                return GeminiErrorKind.RATE_LIMITED
            }
            if (code == 503 || msg.contains("503") || msg.contains("unavailable") || msg.contains("high demand") || msg.contains("overloaded")) {
                return GeminiErrorKind.OVERLOADED
            }
            if (code == 400 || msg.contains("400") || msg.contains("invalid_argument") || msg.contains("safety") || msg.contains("blocked")) {
                return GeminiErrorKind.BAD_REQUEST
            }
            if (code in listOf(500, 502, 504) || cause is SocketException || cause is IOException) {
                return GeminiErrorKind.TRANSIENT
            }
            return GeminiErrorKind.TRANSIENT
        }

        fun getFriendlyMessage(kind: GeminiErrorKind): String {
            return when (kind) {
                GeminiErrorKind.OVERLOADED -> "Gemini abhi busy hai (high demand). Thodi der baad dobara try kijiye."
                GeminiErrorKind.TIMEOUT -> "Jawab aane me der lag rahi hai. Dobara try kijiye."
                GeminiErrorKind.RATE_LIMITED -> "Request limit poori ho gayi hai. Thodi der baad try kijiye."
                GeminiErrorKind.AUTH -> "API key ya billing check kijiye (API Connection screen)."
                GeminiErrorKind.TRANSIENT, GeminiErrorKind.MODEL_GONE -> "Internet ya server me problem aayi. Dobara try kijiye."
                GeminiErrorKind.BAD_REQUEST -> "Ye request process nahi ho paayi. Alag tareeke se likh kar dekhiye."
            }
        }

        fun ensureFreshTimeInSystemInstruction(instruction: String?): String {
            val freshTimeLine = "Current local time: ${TimeHelper.getFreshFormattedTime()}"
            return if (instruction.isNullOrBlank()) {
                freshTimeLine
            } else if (instruction.contains("Current local time:")) {
                instruction
            } else {
                "$instruction\n\n$freshTimeLine"
            }
        }

        fun buildRequestBodyJson(
            systemInstruction: String?,
            contents: List<RestContent>,
            generationConfig: RestGenerationConfig? = null,
            tools: List<RestTool>? = null
        ): String {
            val finalInstruction = ensureFreshTimeInSystemInstruction(systemInstruction)
            val sysInst = RestSystemInstruction(listOf(RestPart(text = finalInstruction)))
            val body = RestRequestBody(
                systemInstruction = sysInst,
                contents = contents,
                generationConfig = generationConfig,
                tools = tools
            )
            return defaultGson.toJson(body)
        }

        fun parseResponseBodyJson(json: String, modelUsed: String = ""): GeminiRestResult {
            val response = try {
                defaultGson.fromJson(json, RestResponseBody::class.java)
            } catch (e: Exception) {
                throw IOException("Invalid JSON response from server: ${e.message}")
            } ?: throw IOException("Empty response received from server.")

            response.error?.let { err ->
                val kind = classifyError(err.code, err.message, null)
                throw GeminiApiException(kind, getFriendlyMessage(kind))
            }

            response.promptFeedback?.blockReason?.let { blockReason ->
                throw GeminiApiException(GeminiErrorKind.BAD_REQUEST, getFriendlyMessage(GeminiErrorKind.BAD_REQUEST))
            }

            val candidates = response.candidates
            if (candidates.isNullOrEmpty()) {
                throw GeminiApiException(GeminiErrorKind.TRANSIENT, getFriendlyMessage(GeminiErrorKind.TRANSIENT))
            }

            val primary = candidates[0]
            if (primary.finishReason.equals("SAFETY", ignoreCase = true)) {
                throw GeminiApiException(GeminiErrorKind.BAD_REQUEST, getFriendlyMessage(GeminiErrorKind.BAD_REQUEST))
            }

            val textParts = mutableListOf<String>()
            val imageParts = mutableListOf<RestInlineData>()
            val functionCalls = mutableListOf<RestFunctionCall>()

            primary.content?.parts?.forEach { part ->
                if (part.thought == true) {
                    return@forEach
                }
                if (!part.text.isNullOrEmpty()) {
                    textParts.add(part.text)
                }
                if (part.inlineData != null) {
                    imageParts.add(part.inlineData)
                }
                if (part.functionCall != null) {
                    functionCalls.add(part.functionCall)
                }
            }

            val fullText = textParts.joinToString("\n\n").trim().ifEmpty { null }
            return GeminiRestResult(
                text = fullText,
                inlineImages = imageParts,
                functionCalls = functionCalls,
                modelUsed = modelUsed
            )
        }
    }

    /**
     * Streams responses using SSE from streamGenerateContent endpoint.
     * Retries and falls back through the model chain with an overall 3-minute cap.
     */
    suspend fun streamGenerateContent(
        apiKey: String,
        systemInstruction: String?,
        contents: List<RestContent>,
        models: List<String> = GeminiConstants.CODE_MODELS,
        generationConfig: RestGenerationConfig? = null,
        tools: List<RestTool>? = null,
        onChunk: (accumulatedText: String, newText: String) -> Unit = { _, _ -> }
    ): Result<GeminiStreamResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            val msg = getFriendlyMessage(GeminiErrorKind.AUTH)
            return@withContext Result.failure(GeminiApiException(GeminiErrorKind.AUTH, msg))
        }

        val candidateModels = getOrderedModels(models.filter { it.isNotBlank() })
        if (candidateModels.isEmpty()) {
            val msg = getFriendlyMessage(GeminiErrorKind.BAD_REQUEST)
            return@withContext Result.failure(GeminiApiException(GeminiErrorKind.BAD_REQUEST, msg))
        }

        val config = (generationConfig ?: RestGenerationConfig()).copy(maxOutputTokens = 8192)
        val requestBodyJson = buildRequestBodyJson(
            systemInstruction = systemInstruction,
            contents = contents,
            generationConfig = config,
            tools = tools
        )

        val overallStartTime = System.currentTimeMillis()
        val overallCapMs = 180_000L // 3 minutes overall cap
        var lastError: GeminiApiException? = null

        for (modelName in candidateModels) {
            if (System.currentTimeMillis() - overallStartTime >= overallCapMs) {
                break
            }

            val cleanModel = modelName.removePrefix("models/")
            val url = "$BASE_URL/$cleanModel:streamGenerateContent?alt=sse"

            var attempt = 0
            val maxAttemptsForModel = 2

            while (attempt < maxAttemptsForModel) {
                if (System.currentTimeMillis() - overallStartTime >= overallCapMs) {
                    break
                }
                attempt++

                try {
                    val streamResult = executeSingleStreamingAttempt(
                        url = url,
                        apiKey = cleanKey,
                        bodyJson = requestBodyJson,
                        modelName = modelName,
                        onChunk = onChunk
                    )
                    recordWorkingModel(modelName)
                    return@withContext Result.success(streamResult)
                } catch (e: GeminiApiException) {
                    lastError = e
                    when (e.kind) {
                        GeminiErrorKind.AUTH, GeminiErrorKind.BAD_REQUEST -> {
                            // Hard stop on Auth or Bad Request
                            return@withContext Result.failure(e)
                        }
                        GeminiErrorKind.MODEL_GONE -> {
                            // Immediately move to next model without retrying current
                            break
                        }
                        GeminiErrorKind.OVERLOADED, GeminiErrorKind.RATE_LIMITED,
                        GeminiErrorKind.TRANSIENT, GeminiErrorKind.TIMEOUT -> {
                            if (attempt < maxAttemptsForModel && (System.currentTimeMillis() - overallStartTime + 1500L < overallCapMs)) {
                                delay(1500L) // Retry same model once after ~1.5s
                            } else {
                                break // Move to next model
                            }
                        }
                    }
                } catch (e: Exception) {
                    val kind = classifyError(null, e.message, e)
                    val apiEx = GeminiApiException(kind, getFriendlyMessage(kind), e)
                    lastError = apiEx
                    if (kind == GeminiErrorKind.AUTH || kind == GeminiErrorKind.BAD_REQUEST) {
                        return@withContext Result.failure(apiEx)
                    }
                    if (attempt < maxAttemptsForModel && (System.currentTimeMillis() - overallStartTime + 1500L < overallCapMs)) {
                        delay(1500L)
                    } else {
                        break
                    }
                }
            }
        }

        val fallbackError = lastError ?: GeminiApiException(
            GeminiErrorKind.TIMEOUT,
            getFriendlyMessage(GeminiErrorKind.TIMEOUT)
        )
        Result.failure(fallbackError)
    }

    private suspend fun executeSingleStreamingAttempt(
        url: String,
        apiKey: String,
        bodyJson: String,
        modelName: String,
        onChunk: (accumulatedText: String, newText: String) -> Unit
    ): GeminiStreamResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = streamingClient.newCall(request)
        val accumulatedText = StringBuilder()
        var finalFinishReason: String? = null
        var isMaxTokens = false
        val firstTokenReceived = AtomicBoolean(false)
        val requestStartTime = System.currentTimeMillis()

        call.execute().use { response ->
            val code = response.code
            if (!response.isSuccessful) {
                val errBody = try { response.body?.string().orEmpty() } catch (_: Exception) { "" }
                val kind = classifyError(code, errBody, null)
                throw GeminiApiException(kind, getFriendlyMessage(kind))
            }

            val body = response.body ?: throw GeminiApiException(
                GeminiErrorKind.TRANSIENT,
                getFriendlyMessage(GeminiErrorKind.TRANSIENT)
            )

            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                if (currentLine.startsWith("data:")) {
                    val dataJson = currentLine.substring(5).trim()
                    if (dataJson.isEmpty() || dataJson == "[DONE]") {
                        continue
                    }

                    // Check for first token timeout (20s)
                    if (!firstTokenReceived.get() && (System.currentTimeMillis() - requestStartTime > 20_000L)) {
                        throw GeminiApiException(GeminiErrorKind.TIMEOUT, getFriendlyMessage(GeminiErrorKind.TIMEOUT))
                    }

                    try {
                        val chunkObj = gson.fromJson(dataJson, RestResponseBody::class.java)
                        chunkObj?.error?.let { err ->
                            val kind = classifyError(err.code, err.message, null)
                            throw GeminiApiException(kind, getFriendlyMessage(kind))
                        }

                        val candidates = chunkObj?.candidates
                        if (!candidates.isNullOrEmpty()) {
                            val candidate = candidates[0]
                            if (candidate.finishReason != null) {
                                finalFinishReason = candidate.finishReason
                                if (candidate.finishReason.equals("MAX_TOKENS", ignoreCase = true)) {
                                    isMaxTokens = true
                                }
                            }

                            candidate.content?.parts?.forEach { part ->
                                if (part.thought == true) {
                                    return@forEach
                                }
                                val text = part.text
                                if (!text.isNullOrEmpty()) {
                                    accumulatedText.append(text)
                                    firstTokenReceived.set(true)
                                    onChunk(accumulatedText.toString(), text)
                                }
                            }
                        }
                    } catch (e: GeminiApiException) {
                        throw e
                    } catch (_: Exception) {
                        // Tolerate unparseable intermediate chunk
                    }
                }
            }
        }

        if (accumulatedText.isEmpty()) {
            throw GeminiApiException(GeminiErrorKind.TIMEOUT, getFriendlyMessage(GeminiErrorKind.TIMEOUT))
        }

        GeminiStreamResult(
            text = accumulatedText.toString(),
            modelUsed = modelName,
            finishReason = finalFinishReason,
            isMaxTokens = isMaxTokens
        )
    }

    suspend fun generateContent(
        apiKey: String,
        systemInstruction: String?,
        contents: List<RestContent>,
        models: List<String> = listOf(GeminiConstants.CODE_MODEL, GeminiConstants.FALLBACK_TEXT_MODEL),
        generationConfig: RestGenerationConfig? = null,
        tools: List<RestTool>? = null,
        isImageRequest: Boolean = false
    ): Result<GeminiRestResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            val msg = getFriendlyMessage(GeminiErrorKind.AUTH)
            return@withContext Result.failure(GeminiApiException(GeminiErrorKind.AUTH, msg))
        }

        val candidateModels = getOrderedModels(models.filter { it.isNotBlank() })
        if (candidateModels.isEmpty()) {
            val msg = getFriendlyMessage(GeminiErrorKind.BAD_REQUEST)
            return@withContext Result.failure(GeminiApiException(GeminiErrorKind.BAD_REQUEST, msg))
        }

        val requestBodyJson = buildRequestBodyJson(
            systemInstruction = systemInstruction,
            contents = contents,
            generationConfig = generationConfig,
            tools = tools
        )

        val client = if (isImageRequest) imageHttpClient else httpClient
        var lastException: Exception? = null

        for (modelName in candidateModels) {
            val cleanModel = modelName.removePrefix("models/")
            val url = "$BASE_URL/$cleanModel:generateContent"

            var attempt = 0
            val maxAttempts = 2

            while (attempt < maxAttempts) {
                attempt++
                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", cleanKey)
                    .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        val code = response.code
                        val responseBodyString = response.body?.string() ?: ""

                        if (response.isSuccessful) {
                            val parsed = parseResponseBodyJson(responseBodyString, modelName)
                            recordWorkingModel(modelName)
                            return@withContext Result.success(parsed)
                        }

                        val kind = classifyError(code, responseBodyString, null)
                        val apiEx = GeminiApiException(kind, getFriendlyMessage(kind))
                        lastException = apiEx

                        if (kind == GeminiErrorKind.AUTH || kind == GeminiErrorKind.BAD_REQUEST) {
                            return@withContext Result.failure(apiEx)
                        }

                        if (kind == GeminiErrorKind.MODEL_GONE) {
                            break // next model
                        }

                        if (attempt < maxAttempts) {
                            delay(1500)
                        }
                    }
                } catch (e: Exception) {
                    val kind = classifyError(null, e.message, e)
                    val apiEx = GeminiApiException(kind, getFriendlyMessage(kind), e)
                    lastException = apiEx
                    if (kind == GeminiErrorKind.AUTH || kind == GeminiErrorKind.BAD_REQUEST) {
                        return@withContext Result.failure(apiEx)
                    }
                    if (attempt < maxAttempts) {
                        delay(1500)
                    }
                }
            }
        }

        val finalError = lastException ?: GeminiApiException(
            GeminiErrorKind.TRANSIENT,
            getFriendlyMessage(GeminiErrorKind.TRANSIENT)
        )
        Result.failure(finalError)
    }
}
