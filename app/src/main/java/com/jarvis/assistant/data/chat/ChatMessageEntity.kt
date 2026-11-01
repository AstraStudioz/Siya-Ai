package com.jarvis.assistant.data.chat

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val messageId: Long = 0,
    val sessionId: String,
    val userTranscript: String,
    val jarvisResponse: String,
    val attachmentsJson: String? = null,
    val generatedImagesJson: String? = null,
    val isError: Boolean = false,
    val isMaxTokens: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
