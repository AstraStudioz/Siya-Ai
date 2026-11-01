package com.jarvis.assistant.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatHistorySessionTest {

    @Test
    fun testChatSessionEntityDefaults() {
        val id = UUID.randomUUID().toString()
        val session = ChatSessionEntity(
            id = id,
            title = "New Chat"
        )

        assertEquals(id, session.id)
        assertEquals("New Chat", session.title)
        assertFalse(session.isPinned)
        assertTrue(session.createdAt > 0L)
        assertTrue(session.updatedAt >= session.createdAt)
    }

    @Test
    fun testAutoTitleGenerationLogic() {
        fun generateAutoTitle(userText: String): String {
            val clean = userText.trim().replace(Regex("[\\n\\r]+"), " ")
            return if (clean.length > 32) clean.take(30).trim() + "…" else clean.ifEmpty { "Conversation" }
        }

        val shortQuery = "Tiger ki image banao"
        assertEquals("Tiger ki image banao", generateAutoTitle(shortQuery))

        val longQuery = "Can you help me write an Android Jetpack Compose application with Kotlin coroutines?"
        val title = generateAutoTitle(longQuery)
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= 32)

        val emptyQuery = "    "
        assertEquals("Conversation", generateAutoTitle(emptyQuery))
    }

    @Test
    fun testChatMessageEntityCreation() {
        val sessionId = UUID.randomUUID().toString()
        val message = ChatMessageEntity(
            sessionId = sessionId,
            userTranscript = "Hello Siya",
            jarvisResponse = "Hello! How can I assist you today?",
            attachmentsJson = null,
            generatedImagesJson = null,
            isError = false
        )

        assertEquals(sessionId, message.sessionId)
        assertEquals("Hello Siya", message.userTranscript)
        assertEquals("Hello! How can I assist you today?", message.jarvisResponse)
        assertFalse(message.isError)
    }
}
