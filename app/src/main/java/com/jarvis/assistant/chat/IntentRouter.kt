package com.jarvis.assistant.chat

import com.jarvis.assistant.network.GeminiRestClient
import com.jarvis.assistant.network.RestContent
import com.jarvis.assistant.network.RestPart
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

enum class ChatIntent {
    CHAT,
    IMAGE,
    IMAGE_EDIT,
    CODE
}

data class IntentResult(
    val intent: ChatIntent,
    val confidence: Float,
    val suggestedMode: String, // "siya", "image", "code"
    val reason: String
)

object IntentRouter {

    private val IMAGE_CREATION_VERBS = listOf(
        "banao", "bana do", "bana de", "bana", "generate", "create", "draw", "design",
        "sketch", "dikhao", "dikhaye", "make", "render", "paint", "draw karo", "generate karo",
        "bana ke do"
    )

    private val IMAGE_NOUNS = listOf(
        "image", "images", "photo", "photos", "picture", "pictures", "tasveer", "tasveerein",
        "tasveeren", "logo", "wallpaper", "poster", "illustration", "thumbnail", "banner",
        "avatar", "art", "sticker", "drawing", "painting", "pic", "pics"
    )

    private val IMAGE_EDIT_VERBS = listOf(
        "edit", "change", "remove", "replace", "cartoon", "badal do", "badlo", "hata do",
        "modify", "filter", "erase", "background badal", "color badal", "add filter", "retouch"
    )

    private val CODE_KEYWORDS = listOf(
        "code", "script", "program", "function", "html", "css", "javascript", "js", "python",
        "kotlin", "java", "sql", "api", "website", "app", "bug", "error", "code do",
        "code likho", "function likho", "script do", "algorithm", "regex", "json parser",
        "backend code", "frontend code"
    )

    private val EXPLANATION_OR_CONCEPT_PATTERNS = listOf(
        "how does", "how do", "how to use", "what is", "what are", "what does",
        "explain", "difference between", "meaning of", "kaise kaam karta",
        "kaise kaam karti", "kaise kaam karta hai", "kya hota hai", "kya hoti hai",
        "kya hai", "batao kya", "samjhao", "concept of", "history of",
        "tell me about", "who is", "who created", "why is", "why does"
    )

    /**
     * Step 1: Pure local deterministic rule engine.
     * Evaluates Hindi, Hinglish, and English text with zero network calls.
     */
    fun routeLocal(
        text: String,
        currentMode: String = "siya",
        hasAttachedImage: Boolean = false
    ): IntentResult {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        if (lower.isEmpty()) {
            return if (hasAttachedImage) {
                IntentResult(ChatIntent.IMAGE_EDIT, 0.75f, "image", "Attached image without text")
            } else {
                IntentResult(ChatIntent.CHAT, 0.9f, "siya", "Empty input defaults to chat")
            }
        }

        // Rule A: Explanations, educational inquiries, and definitions MUST stay CHAT even if they mention 'code' or 'api'
        // e.g., "how does code work?", "what is python?", "explain what an API is", "code kya hota hai"
        val isExplanation = EXPLANATION_OR_CONCEPT_PATTERNS.any { lower.contains(it) }
        if (isExplanation) {
            return IntentResult(
                intent = ChatIntent.CHAT,
                confidence = 0.95f,
                suggestedMode = "siya",
                reason = "Inquiry is an explanation/definition, stays in chat"
            )
        }

        // Rule B: Fenced code blocks in the message immediately qualify as CODE
        if (lower.contains("```")) {
            return IntentResult(
                intent = ChatIntent.CODE,
                confidence = 0.98f,
                suggestedMode = "code",
                reason = "Contains markdown fenced code block"
            )
        }

        // Rule C: IMAGE_EDIT - An attached image plus edit / modification verbs
        if (hasAttachedImage) {
            val hasEditVerb = IMAGE_EDIT_VERBS.any { lower.contains(it) }
            if (hasEditVerb) {
                return IntentResult(
                    intent = ChatIntent.IMAGE_EDIT,
                    confidence = 0.95f,
                    suggestedMode = "image",
                    reason = "Attached image with modification request"
                )
            }
        }

        // Rule D: IMAGE generation - creation verb + image noun
        val hasCreationVerb = IMAGE_CREATION_VERBS.any { containsWordOrPhrase(lower, it) }
        val hasImageNoun = IMAGE_NOUNS.any { containsWordOrPhrase(lower, it) }

        if (hasCreationVerb && hasImageNoun) {
            return IntentResult(
                intent = ChatIntent.IMAGE,
                confidence = 0.92f,
                suggestedMode = "image",
                reason = "Matched creation verb and image noun"
            )
        }

        // Rule E: CODE generation - programming keywords and explicit requests
        val hasExplicitCodeCommand = lower.contains("code do") || lower.contains("code likho") ||
                lower.contains("function likho") || lower.contains("script do") ||
                lower.contains("write code") || lower.contains("write a function") ||
                lower.contains("write a script") || lower.contains("write a program") ||
                lower.contains("create a website") || lower.contains("build an app")

        val hasCodeKeyword = CODE_KEYWORDS.any { containsWordOrPhrase(lower, it) }

        if (hasExplicitCodeCommand || hasCodeKeyword) {
            // Check if it's chit-chat or generic conversation that merely used a word casually
            val isActionableCoding = hasExplicitCodeCommand ||
                    lower.contains("function") || lower.contains("html") ||
                    lower.contains("css") || lower.contains("javascript") ||
                    lower.contains("python") || lower.contains("kotlin") ||
                    lower.contains("sql") || lower.contains("script") ||
                    lower.contains("algorithm") || lower.contains("bug") ||
                    (lower.contains("code") && (hasCreationVerb || lower.contains("for") || lower.contains("in")))

            if (isActionableCoding) {
                return IntentResult(
                    intent = ChatIntent.CODE,
                    confidence = 0.90f,
                    suggestedMode = "code",
                    reason = "Explicit coding request or actionable programming keyword"
                )
            }
        }

        // Rule F: Image standalone without explicit verb (e.g. "sunset wallpaper 4k", "cyberpunk anime avatar")
        if (hasImageNoun && (lower.contains("wallpaper") || lower.contains("poster") || lower.contains("avatar") || lower.contains("logo"))) {
            return IntentResult(
                intent = ChatIntent.IMAGE,
                confidence = 0.85f,
                suggestedMode = "image",
                reason = "Direct visual artifact query"
            )
        }

        // Default: Conversational CHAT
        return IntentResult(
            intent = ChatIntent.CHAT,
            confidence = 0.85f,
            suggestedMode = "siya",
            reason = "Conversational chat baseline"
        )
    }

    private fun containsWordOrPhrase(source: String, target: String): Boolean {
        if (!target.contains(" ")) {
            val regex = Regex("\\b${Regex.escape(target)}\\b", RegexOption.IGNORE_CASE)
            return regex.containsMatchIn(source)
        }
        return source.contains(target)
    }

    /**
     * Step 2: Fallback classifier with model "gemini-3.5-flash-lite" (2s timeout).
     * Used only when local rules are ambiguous (confidence < 0.75).
     * On failure or low confidence, defaults gracefully to CHAT.
     */
    suspend fun routeWithClassifier(
        text: String,
        currentMode: String = "siya",
        hasAttachedImage: Boolean = false,
        apiKey: String,
        restClient: GeminiRestClient = GeminiRestClient()
    ): IntentResult {
        val local = routeLocal(text, currentMode, hasAttachedImage)
        if (local.confidence >= 0.75f || apiKey.isBlank()) {
            return local
        }

        return try {
            val classified = withTimeoutOrNull(2000L) {
                val prompt = """
                    Classify the intent of the following user message into exactly ONE of:
                    - CHAT: General conversation, greeting, explanation, or questions about how things work.
                    - IMAGE: Request to generate, draw, or create a new picture/illustration/artwork.
                    - IMAGE_EDIT: Request to modify, edit, or filter an existing image.
                    - CODE: Request to write, fix, or provide programming code, scripts, HTML, or functions.

                    Respond with raw JSON only (no markdown, no quotes):
                    {"intent":"CHAT|IMAGE|IMAGE_EDIT|CODE","confidence":0.0 to 1.0}

                    User message: "$text"
                    Has attached image: $hasAttachedImage
                """.trimIndent()

                val contents = listOf(
                    RestContent(
                        role = "user",
                        parts = listOf(RestPart(text = prompt))
                    )
                )

                val result = restClient.generateContent(
                    apiKey = apiKey,
                    systemInstruction = "You are a fast intent classifier. Return valid raw JSON only.",
                    contents = contents,
                    models = listOf("gemini-3.5-flash-lite")
                )

                result.getOrNull()?.text?.let { rawJson ->
                    val clean = rawJson.replace("```json", "").replace("```", "").trim()
                    val obj = JSONObject(clean)
                    val intentStr = obj.optString("intent", "CHAT").uppercase(Locale.ROOT)
                    val conf = obj.optDouble("confidence", 0.5).toFloat()
                    val intent = when (intentStr) {
                        "IMAGE" -> ChatIntent.IMAGE
                        "IMAGE_EDIT" -> ChatIntent.IMAGE_EDIT
                        "CODE" -> ChatIntent.CODE
                        else -> ChatIntent.CHAT
                    }
                    val mode = when (intent) {
                        ChatIntent.IMAGE, ChatIntent.IMAGE_EDIT -> "image"
                        ChatIntent.CODE -> "code"
                        ChatIntent.CHAT -> "siya"
                    }
                    IntentResult(intent, conf, mode, "Gemini classifier")
                }
            }

            if (classified != null && classified.confidence >= 0.70f) {
                classified
            } else {
                local
            }
        } catch (_: Exception) {
            local
        }
    }
}
