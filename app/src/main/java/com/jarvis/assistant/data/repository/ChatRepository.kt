package com.jarvis.assistant.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.chat.ChatDatabase
import com.jarvis.assistant.data.chat.ChatMessageEntity
import com.jarvis.assistant.data.chat.ChatSessionEntity
import com.jarvis.assistant.data.model.ChatAttachment
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

class ChatRepository(
    private val preferences: AppPreferences,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "ChatRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val chatDb: ChatDatabase? = context?.let { ChatDatabase.getInstance(it) }

    private val _activeSessionId = MutableStateFlow<String>("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _activeSessionTitle = MutableStateFlow<String>("Hi")
    val activeSessionTitle: StateFlow<String> = _activeSessionTitle.asStateFlow()

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        initializeSessions()
    }

    private fun initializeSessions() {
        if (chatDb == null) {
            _turns.value = preferences.loadChatHistory()
            return
        }

        try {
            runBlocking(Dispatchers.IO) {
                val dao = chatDb.chatDao()
                val sessionCount = dao.getSessionCount()

                if (sessionCount == 0) {
                    // Check for existing single history from preferences for zero data loss migration
                    val legacyTurns = preferences.loadChatHistory()
                    val initialId = UUID.randomUUID().toString()
                    val initialTitle = if (legacyTurns.isNotEmpty()) {
                        val firstUserTurn = legacyTurns.firstOrNull { it.userTranscript.isNotBlank() }
                        firstUserTurn?.userTranscript?.let { generateAutoTitle(it) } ?: "Previous Chat"
                    } else {
                        "New Chat"
                    }

                    val initialSession = ChatSessionEntity(
                        id = initialId,
                        title = initialTitle,
                        isPinned = false,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.insertSession(initialSession)

                    legacyTurns.forEach { turn ->
                        dao.insertMessage(
                            ChatMessageEntity(
                                sessionId = initialId,
                                userTranscript = turn.userTranscript,
                                jarvisResponse = turn.jarvisResponse,
                                attachmentsJson = turn.attachments?.let { gson.toJson(it) },
                                generatedImagesJson = turn.generatedImages?.let { gson.toJson(it) },
                                isError = turn.isError == true,
                                isMaxTokens = turn.isMaxTokens == true,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }

                    _activeSessionId.value = initialId
                    _activeSessionTitle.value = initialTitle
                    _turns.value = legacyTurns
                    Log.i(TAG, "Migrated ${legacyTurns.size} legacy turns to session $initialId ('$initialTitle')")
                } else {
                    val allSessions = dao.getAllSessions()
                    val latestSession = allSessions.firstOrNull()
                    if (latestSession != null) {
                        _activeSessionId.value = latestSession.id
                        _activeSessionTitle.value = latestSession.title
                        loadSessionTurns(latestSession.id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing chat sessions: ${e.message}", e)
            _turns.value = preferences.loadChatHistory()
        }
    }

    private suspend fun loadSessionTurns(sessionId: String) {
        val dao = chatDb?.chatDao() ?: return
        val messages = dao.getMessages(sessionId)
        val loadedTurns = messages.map { entity ->
            val atts: List<ChatAttachment>? = if (!entity.attachmentsJson.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<List<ChatAttachment>>() {}.type
                    gson.fromJson(entity.attachmentsJson, type)
                } catch (_: Exception) { null }
            } else null

            val imgs: List<String>? = if (!entity.generatedImagesJson.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(entity.generatedImagesJson, type)
                } catch (_: Exception) { null }
            } else null

            ChatTurn(
                userTranscript = entity.userTranscript,
                jarvisResponse = entity.jarvisResponse,
                attachments = atts,
                generatedImages = imgs,
                isError = if (entity.isError) true else null,
                isMaxTokens = if (entity.isMaxTokens) true else null
            )
        }
        _turns.value = loadedTurns
    }

    fun getAllSessionsFlow(): Flow<List<ChatSessionEntity>> {
        return chatDb?.chatDao()?.getAllSessionsFlow()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    fun createNewSession(title: String = "New Chat"): String {
        val newId = UUID.randomUUID().toString()
        val newSession = ChatSessionEntity(
            id = newId,
            title = title,
            isPinned = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        scope.launch {
            chatDb?.chatDao()?.insertSession(newSession)
        }
        _activeSessionId.value = newId
        _activeSessionTitle.value = title
        _turns.value = emptyList()
        return newId
    }

    fun selectSession(sessionId: String) {
        if (_activeSessionId.value == sessionId) return
        scope.launch {
            val session = chatDb?.chatDao()?.getSessionById(sessionId) ?: return@launch
            _activeSessionId.value = session.id
            _activeSessionTitle.value = session.title
            loadSessionTurns(session.id)
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val cleanTitle = newTitle.trim().ifEmpty { "Chat" }
        scope.launch {
            chatDb?.chatDao()?.updateTitle(sessionId, cleanTitle)
            if (_activeSessionId.value == sessionId) {
                _activeSessionTitle.value = cleanTitle
            }
        }
    }

    fun setSessionPinned(sessionId: String, isPinned: Boolean) {
        scope.launch {
            chatDb?.chatDao()?.setPinned(sessionId, isPinned)
        }
    }

    fun deleteSession(sessionId: String) {
        scope.launch {
            val dao = chatDb?.chatDao() ?: return@launch
            dao.deleteSessionById(sessionId)
            if (_activeSessionId.value == sessionId) {
                val remaining = dao.getAllSessions()
                if (remaining.isNotEmpty()) {
                    val next = remaining.first()
                    _activeSessionId.value = next.id
                    _activeSessionTitle.value = next.title
                    loadSessionTurns(next.id)
                } else {
                    val createdId = createNewSession("New Chat")
                    _activeSessionId.value = createdId
                    _activeSessionTitle.value = "New Chat"
                    _turns.value = emptyList()
                }
            }
        }
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        addTurn(userText, jarvisText, null, null)
    }

    @Synchronized
    fun addTurn(
        userText: String,
        jarvisText: String,
        attachments: List<ChatAttachment>? = null,
        generatedImages: List<String>? = null,
        isError: Boolean? = null,
        isMaxTokens: Boolean? = null
    ) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty() && attachments.isNullOrEmpty() && generatedImages.isNullOrEmpty()) {
            return
        }

        var currentSessionId = _activeSessionId.value
        if (currentSessionId.isBlank()) {
            currentSessionId = createNewSession("New Chat")
        }

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser &&
                last.jarvisResponse == cleanJarvis &&
                last.attachments == attachments &&
                last.generatedImages == generatedImages &&
                last.isError == isError &&
                last.isMaxTokens == isMaxTokens
            ) {
                return
            }
            if (last.userTranscript == cleanUser &&
                (last.jarvisResponse == "(Audio response)" || last.jarvisResponse.isEmpty()) &&
                cleanJarvis.isNotEmpty()
            ) {
                currentList[currentList.size - 1] = last.copy(jarvisResponse = cleanJarvis)
                _turns.value = currentList
                preferences.saveChatHistory(currentList)
                updateLastTurnInDb(currentSessionId, cleanJarvis)
                return
            }
        }

        val newTurn = ChatTurn(
            userTranscript = if (cleanUser.isNotEmpty()) cleanUser else if (!attachments.isNullOrEmpty()) "(Attachment)" else "(Audio query)",
            jarvisResponse = cleanJarvis.ifEmpty { "(Audio response)" },
            attachments = attachments,
            generatedImages = generatedImages,
            isError = isError,
            isMaxTokens = isMaxTokens
        )

        currentList.add(newTurn)
        _turns.value = currentList
        preferences.saveChatHistory(currentList)

        // Auto-title session from the first user message if still titled "New Chat"
        val currentTitle = _activeSessionTitle.value
        val shouldAutoTitle = (currentTitle == "New Chat" || currentTitle == "Hi" || currentTitle.isBlank()) && cleanUser.isNotBlank()
        val newTitle = if (shouldAutoTitle) generateAutoTitle(cleanUser) else null

        if (newTitle != null) {
            _activeSessionTitle.value = newTitle
        }

        scope.launch {
            val dao = chatDb?.chatDao() ?: return@launch
            if (newTitle != null) {
                dao.updateTitle(currentSessionId, newTitle)
            } else {
                dao.touchSession(currentSessionId)
            }

            dao.insertMessage(
                ChatMessageEntity(
                    sessionId = currentSessionId,
                    userTranscript = newTurn.userTranscript,
                    jarvisResponse = newTurn.jarvisResponse,
                    attachmentsJson = newTurn.attachments?.let { gson.toJson(it) },
                    generatedImagesJson = newTurn.generatedImages?.let { gson.toJson(it) },
                    isError = newTurn.isError == true,
                    isMaxTokens = newTurn.isMaxTokens == true,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private fun updateLastTurnInDb(sessionId: String, jarvisResponse: String) {
        scope.launch {
            val dao = chatDb?.chatDao() ?: return@launch
            val messages = dao.getMessages(sessionId)
            if (messages.isNotEmpty()) {
                val lastMsg = messages.last()
                dao.updateMessageResponse(lastMsg.messageId, jarvisResponse)
                dao.touchSession(sessionId)
            }
        }
    }

    private fun generateAutoTitle(userText: String): String {
        val clean = userText.trim().replace(Regex("[\\n\\r]+"), " ")
        return if (clean.length > 32) clean.take(30).trim() + "…" else clean.ifEmpty { "Conversation" }
    }

    @Synchronized
    fun clearHistory() {
        val oldTurns = _turns.value
        oldTurns.forEach { deleteTurnFiles(it) }

        context?.let { ctx ->
            try {
                val mediaDir = File(ctx.filesDir, "chat_media")
                if (mediaDir.exists() && mediaDir.isDirectory) {
                    mediaDir.listFiles()?.forEach { file ->
                        try { file.delete() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        val sessionId = _activeSessionId.value
        _turns.value = emptyList()
        preferences.clearChatHistory()

        scope.launch {
            if (sessionId.isNotBlank()) {
                chatDb?.chatDao()?.deleteMessagesForSession(sessionId)
                chatDb?.chatDao()?.touchSession(sessionId)
            }
        }
    }

    private fun deleteTurnFiles(turn: ChatTurn) {
        try {
            turn.attachments?.forEach { att ->
                try {
                    val f = File(att.path)
                    if (f.exists()) f.delete()
                } catch (_: Exception) {}
            }
            turn.generatedImages?.forEach { path ->
                try {
                    val f = File(path)
                    if (f.exists()) f.delete()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
