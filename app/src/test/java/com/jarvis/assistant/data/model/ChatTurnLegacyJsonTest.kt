package com.jarvis.assistant.data.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnLegacyJsonTest {

    @Test
    fun testLoadingOldSavedChatHistoryWithoutNewFields() {
        val legacyJson = """
            [
              {
                "id": "legacy-turn-1",
                "userTranscript": "Who made you?",
                "jarvisResponse": "I was created and developed by ShivuOfc!",
                "timestamp": 1729000000000
              },
              {
                "id": "legacy-turn-2",
                "userTranscript": "Play music",
                "jarvisResponse": "Sir, playing Tadipaar on YouTube.",
                "timestamp": 1729000050000
              }
            ]
        """.trimIndent()

        val gson = Gson()
        val type = object : TypeToken<List<ChatTurn>>() {}.type
        val list: List<ChatTurn> = gson.fromJson(legacyJson, type)

        assertNotNull(list)
        assertEquals(2, list.size)

        val first = list[0]
        assertEquals("legacy-turn-1", first.id)
        assertEquals("Who made you?", first.userTranscript)
        assertEquals("I was created and developed by ShivuOfc!", first.jarvisResponse)
        assertEquals(1729000000000L, first.timestamp)

        // Verify nullable fields are null without NPE
        assertNull(first.attachments)
        assertNull(first.generatedImages)

        // Null-safe access
        val attCount = first.attachments?.size ?: 0
        assertEquals(0, attCount)
        assertTrue(first.attachments.isNullOrEmpty())
        assertTrue(first.generatedImages.isNullOrEmpty())

        // Ensure serialization / deserialization roundtrip preserves fields
        val reserialized = gson.toJson(list)
        val reloaded: List<ChatTurn> = gson.fromJson(reserialized, type)
        assertEquals(2, reloaded.size)
        assertNull(reloaded[0].attachments)
    }
}
