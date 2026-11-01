package com.jarvis.assistant.network

import com.jarvis.assistant.data.model.GeminiConstants
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class GeminiRestClientTest {

    @Test
    fun testBuildRequestBodyJsonIncludesFreshTime() {
        val contents = listOf(
            RestContent(role = "user", parts = listOf(RestPart(text = "Write hello world in python")))
        )
        val json = GeminiRestClient.buildRequestBodyJson(
            systemInstruction = "You are a coding assistant",
            contents = contents
        )

        assertTrue(json.contains("\"systemInstruction\""))
        assertTrue(json.contains("Current local time:"))
        assertTrue(json.contains("You are a coding assistant"))
        assertTrue(json.contains("\"contents\""))
        assertTrue(json.contains("\"Write hello world in python\""))
    }

    @Test
    fun testParseResponseBodyJsonWithTextAndSkipThought() {
        val responseJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "thought": true, "text": "Hidden reasoning thoughts" },
                      { "text": "Here is your python code:\n```python\nprint('hello')\n```" }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val result = GeminiRestClient.parseResponseBodyJson(responseJson, "gemini-3.8-flash")
        assertNotNull(result.text)
        assertTrue(result.text!!.contains("Here is your python code"))
        assertFalse(result.text!!.contains("Hidden reasoning thoughts"))
        assertEquals("gemini-3.8-flash", result.modelUsed)
    }

    @Test
    fun testErrorClassificationAndFriendlyMessages() {
        // OVERLOADED
        val overloadedKind = GeminiRestClient.classifyError(503, "High demand, model overloaded", null)
        assertEquals(GeminiErrorKind.OVERLOADED, overloadedKind)
        val overloadedMsg = GeminiRestClient.getFriendlyMessage(overloadedKind)
        assertEquals("Gemini abhi busy hai (high demand). Thodi der baad dobara try kijiye.", overloadedMsg)

        // TIMEOUT
        val timeoutKind = GeminiRestClient.classifyError(null, "timeout", SocketTimeoutException("timeout"))
        assertEquals(GeminiErrorKind.TIMEOUT, timeoutKind)
        val timeoutMsg = GeminiRestClient.getFriendlyMessage(timeoutKind)
        assertEquals("Jawab aane me der lag rahi hai. Dobara try kijiye.", timeoutMsg)
        assertFalse(timeoutMsg.equals("timeout", ignoreCase = true))

        // RATE_LIMITED
        val rateLimitedKind = GeminiRestClient.classifyError(429, "Resource exhausted: quota exceeded", null)
        assertEquals(GeminiErrorKind.RATE_LIMITED, rateLimitedKind)
        val rateMsg = GeminiRestClient.getFriendlyMessage(rateLimitedKind)
        assertEquals("Request limit poori ho gayi hai. Thodi der baad try kijiye.", rateMsg)

        // AUTH
        val authKind = GeminiRestClient.classifyError(401, "API_KEY_INVALID", null)
        assertEquals(GeminiErrorKind.AUTH, authKind)
        val authMsg = GeminiRestClient.getFriendlyMessage(authKind)
        assertEquals("API key ya billing check kijiye (API Connection screen).", authMsg)

        // TRANSIENT
        val transientKind = GeminiRestClient.classifyError(500, "Internal Server Error", null)
        assertEquals(GeminiErrorKind.TRANSIENT, transientKind)
        val transMsg = GeminiRestClient.getFriendlyMessage(transientKind)
        assertEquals("Internet ya server me problem aayi. Dobara try kijiye.", transMsg)

        // BAD_REQUEST
        val badReqKind = GeminiRestClient.classifyError(400, "INVALID_ARGUMENT", null)
        assertEquals(GeminiErrorKind.BAD_REQUEST, badReqKind)
        val badMsg = GeminiRestClient.getFriendlyMessage(badReqKind)
        assertEquals("Ye request process nahi ho paayi. Alag tareeke se likh kar dekhiye.", badMsg)

        // MODEL_GONE
        val modelGoneKind = GeminiRestClient.classifyError(404, "models/gemini-old is not found", null)
        assertEquals(GeminiErrorKind.MODEL_GONE, modelGoneKind)
    }

    @Test
    fun testParseResponseBodyJsonErrorThrowsFriendlyException() {
        val errorJson = """
            {
              "error": {
                "code": 403,
                "message": "The caller does not have permission",
                "status": "PERMISSION_DENIED"
              }
            }
        """.trimIndent()

        try {
            GeminiRestClient.parseResponseBodyJson(errorJson, "gemini-3.8-flash")
            fail("Expected GeminiApiException for error response")
        } catch (e: GeminiApiException) {
            assertEquals(GeminiErrorKind.AUTH, e.kind)
            assertEquals("API key ya billing check kijiye (API Connection screen).", e.message)
        }
    }

    @Test
    fun testParseResponseBodyJsonSafetyBlock() {
        val safetyJson = """
            {
              "candidates": [
                {
                  "content": { "parts": [] },
                  "finishReason": "SAFETY"
                }
              ]
            }
        """.trimIndent()

        try {
            GeminiRestClient.parseResponseBodyJson(safetyJson, "gemini-3.8-flash")
            fail("Expected GeminiApiException for safety finish reason")
        } catch (e: GeminiApiException) {
            assertEquals(GeminiErrorKind.BAD_REQUEST, e.kind)
            assertEquals("Ye request process nahi ho paayi. Alag tareeke se likh kar dekhiye.", e.message)
        }
    }

    @Test
    fun testModelChainOrderingAndLastWorkingModelCache() {
        val defaultChain = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash")

        // Without cache
        GeminiRestClient.lastWorkingModel = null
        val ordered1 = GeminiRestClient.getOrderedModels(defaultChain)
        assertEquals("gemini-3.8-flash", ordered1[0])

        // When 3.7 succeeds and is cached
        GeminiRestClient.recordWorkingModel("gemini-3.7-flash")
        val ordered2 = GeminiRestClient.getOrderedModels(defaultChain)
        assertEquals("gemini-3.7-flash", ordered2[0])
        assertEquals("gemini-3.8-flash", ordered2[1])
        assertEquals("gemini-3.6-flash", ordered2[2])
    }

    @Test
    fun testStreamingSseWithSplitLinesAndThoughtSkipping() = runBlocking {
        val sseResponseText = """
            data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"Thinking..."}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"<!DOCTYPE html>\n<html>"}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"\n<head><title>Test</title></head>"}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"\n<body><h1>Hello</h1></body></html>"}]},"finishReason":"STOP"}]}

            data: [DONE]
        """.trimIndent()

        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(sseResponseText.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }

        val testClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val restClient = GeminiRestClient(streamingClient = testClient)

        val chunks = mutableListOf<String>()
        val result = restClient.streamGenerateContent(
            apiKey = "test_key",
            systemInstruction = "Code assistant",
            contents = listOf(RestContent("user", listOf(RestPart(text = "HTML page")))),
            models = listOf("gemini-3.8-flash"),
            onChunk = { _, newText ->
                chunks.add(newText)
            }
        )

        assertTrue(result.isSuccess)
        val streamRes = result.getOrNull()
        assertNotNull(streamRes)
        assertTrue(streamRes!!.text.contains("<!DOCTYPE html>"))
        assertTrue(streamRes.text.contains("<h1>Hello</h1>"))
        assertFalse(streamRes.text.contains("Thinking..."))
        assertEquals("STOP", streamRes.finishReason)
        assertEquals(3, chunks.size)
    }

    @Test
    fun testFallbackOn404() = runBlocking {
        val interceptedUrls = mutableListOf<String>()

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            interceptedUrls.add(url)

            if (url.contains("gemini-3.8-flash")) {
                // First model returns 404
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("{\"error\":{\"code\":404,\"message\":\"Model not found\"}}".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                // Fallback model returns 200 OK
                val successBody = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              { "text": "Response from fallback model" }
                            ]
                          }
                        }
                      ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(successBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val testClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val restClient = GeminiRestClient(httpClient = testClient)

        val result = restClient.generateContent(
            apiKey = "test_key",
            systemInstruction = "Test system",
            contents = listOf(RestContent("user", listOf(RestPart(text = "Hello")))),
            models = listOf("gemini-3.8-flash", "gemini-3.7-flash")
        )

        assertTrue(result.isSuccess)
        val res = result.getOrNull()
        assertNotNull(res)
        assertEquals("Response from fallback model", res!!.text)
        assertEquals("gemini-3.7-flash", res.modelUsed)
        assertEquals(2, interceptedUrls.size)
        assertTrue(interceptedUrls[0].contains("gemini-3.8-flash"))
        assertTrue(interceptedUrls[1].contains("gemini-3.7-flash"))
    }
}
