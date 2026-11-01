package com.jarvis.assistant.ui.home

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ChatAttachment
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.network.GeminiRestClient
import com.jarvis.assistant.network.RestContent
import com.jarvis.assistant.network.RestPart
import com.jarvis.assistant.util.AttachmentProcessor
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.PromptGenerator
import com.jarvis.assistant.util.TimeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This ViewModel is intentionally "thin": the microphone, the Gemini Live
 * socket, and the standby/wake-word listener all live inside
 * [JarvisConversationService], not here. This class only mirrors that
 * service's state for the UI and forwards user taps as control intents.
 *
 * That split matters: when the app is removed from Recents, Android destroys
 * the Activity and this ViewModel - if the live conversation lived here, it
 * would die with it. Because it lives in the foreground service instead, the
 * conversation (or standby listening) keeps running untouched, and whenever
 * the UI comes back it just resumes observing whatever the service is doing.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences: AppPreferences = (application as JarvisApp).preferences
    private val restClient = GeminiRestClient()

    val isSessionOn: StateFlow<Boolean> = JarvisConversationService.isSessionOn
    val conversationState: StateFlow<ConversationState> = JarvisConversationService.conversationState
    val audioLevel: StateFlow<Float> = JarvisConversationService.audioLevel
    val audioFrequency: StateFlow<com.jarvis.assistant.audio.AudioFrequencyData> = JarvisConversationService.audioFrequency
    val liveUserTranscript: StateFlow<String> = JarvisConversationService.liveUserTranscript
    val liveAssistantResponse: StateFlow<String> = JarvisConversationService.liveAssistantResponse
    val liveError: StateFlow<com.jarvis.assistant.background.JarvisLiveError?> = JarvisConversationService.liveError

    private val _pendingTurn = MutableStateFlow<ChatTurn?>(null)
    val pendingTurn: StateFlow<ChatTurn?> = _pendingTurn.asStateFlow()

    private val _isRestRunning = MutableStateFlow(false)
    val isRestRunning: StateFlow<Boolean> = _isRestRunning.asStateFlow()

    val chatTurns: StateFlow<List<ChatTurn>> = combine(
        (application as JarvisApp).chatRepository.turns,
        _pendingTurn
    ) { turns, pending ->
        if (pending != null) turns + pending else turns
    }.stateIn(viewModelScope, SharingStarted.Eagerly, (application as JarvisApp).chatRepository.turns.value)

    fun clearLiveError() {
        JarvisConversationService.clearLiveError()
    }

    private val _isMicMuted = MutableStateFlow(preferences.isMicMuted)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    // Surfaces "STANDBY" whenever the service is listening only for the wake
    // word, otherwise mirrors the service's live connection status.
    private val _connectionStatus = MutableStateFlow("OFFLINE")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _liveTime = MutableStateFlow("")
    val liveTime: StateFlow<String> = _liveTime.asStateFlow()

    private val _personalityName = MutableStateFlow(preferences.personality)
    val personalityName: StateFlow<String> = _personalityName.asStateFlow()

    private val _eventFlow = MutableSharedFlow<String>()
    val eventFlow: SharedFlow<String> = _eventFlow.asSharedFlow()

    private var timeClockJob: Job? = null

    init {
        startTimeClock()
        observeServiceStatus()
        observeServiceEvents()
    }

    private fun startTimeClock() {
        timeClockJob = viewModelScope.launch(Dispatchers.Default) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            while (isActive) {
                _liveTime.value = sdf.format(Date())
                delay(1000)
            }
        }
    }

    private fun observeServiceStatus() {
        viewModelScope.launch {
            combine(
                JarvisConversationService.isStandby,
                JarvisConversationService.connectionStatus
            ) { standby, liveStatus -> if (standby) "STANDBY" else liveStatus }
                .collect { _connectionStatus.value = it }
        }
    }

    private fun observeServiceEvents() {
        viewModelScope.launch {
            JarvisConversationService.events.collect { message -> _eventFlow.emit(message) }
        }
        viewModelScope.launch {
            JarvisConversationService.wakeEvents.collect {
                _eventFlow.emit("Wake word detected")
            }
        }
    }

    fun refreshSettings() {
        _personalityName.value = preferences.personality
        _isMicMuted.value = preferences.isMicMuted
        JarvisConversationService.setMicMuted(preferences.isMicMuted)
    }

    fun turnOffSession() {
        sendServiceAction(JarvisConversationService.ACTION_STOP_ALL)
        viewModelScope.launch {
            _eventFlow.emit("Siya Ai Powered Off")
        }
    }

    fun toggleSession() {
        if (JarvisConversationService.isSessionOn.value) {
            turnOffSession()
        } else {
            val context = getApplication<Application>()
            if (!PermissionHelper.hasAllRequiredPermissions(context)) {
                viewModelScope.launch {
                    _eventFlow.emit("Please allow all permissions in Settings to activate Siya Ai.")
                }
                return
            }
            val apiKey = preferences.apiKey.trim()
            if (apiKey.isBlank()) {
                viewModelScope.launch {
                    _eventFlow.emit("Please configure your Gemini API Key in Settings first.")
                }
                return
            }
            sendServiceAction(JarvisConversationService.ACTION_START_CONVERSATION)
        }
    }

    fun toggleMicMute() {
        val newMuted = !_isMicMuted.value
        _isMicMuted.value = newMuted
        preferences.isMicMuted = newMuted
        JarvisConversationService.setMicMuted(newMuted)
        viewModelScope.launch {
            _eventFlow.emit(if (newMuted) "Microphone Muted (Off)" else "Microphone Active (On)")
        }
    }

    fun sendChatMessage(
        text: String,
        attachments: List<ChatAttachment> = emptyList(),
        onStartLiveSession: (() -> Unit)? = null
    ) {
        val cleanText = text.trim()
        if (cleanText.isBlank() && attachments.isEmpty()) return

        val currentMode = preferences.chatMode
        val isImageMode = currentMode == "image"
        val isCodeMode = currentMode == "code"
        val hasAttachments = attachments.isNotEmpty()

        if (isImageMode && !hasAttachments) {
            generateImageMessage(
                prompt = cleanText,
                aspectRatio = preferences.imageAspectRatio,
                stylePreset = preferences.imageStylePreset,
                negativePrompt = preferences.imageNegativePrompt
            )
            return
        }

        if (!isCodeMode && !isImageMode && !hasAttachments) {
            // Siya mode, text only: Live session, voice reply
            if (!JarvisConversationService.isSessionOn.value) {
                onStartLiveSession?.invoke()
            }
            JarvisConversationService.sendTextMessage(cleanText)
            return
        }

        // Code mode or any message with attachments: REST via GeminiRestClient
        val apiKey = preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            val errorMsg = "API key ya billing check kijiye (API Connection screen)."
            (getApplication<Application>() as JarvisApp).chatRepository.addTurn(
                userText = cleanText,
                jarvisText = errorMsg,
                attachments = attachments,
                isError = true
            )
            viewModelScope.launch {
                _eventFlow.emit("Please configure your Gemini API Key in Settings first.")
            }
            return
        }

        viewModelScope.launch {
            _isRestRunning.value = true
            _pendingTurn.value = ChatTurn(
                userTranscript = cleanText.ifEmpty { "(Attachment)" },
                jarvisResponse = "Siya Ai is typing...",
                attachments = attachments
            )

            val systemInstruction = if (isCodeMode) {
                "${PromptGenerator.GENDER_IDENTITY_BLOCK}\n\nCurrent local time: ${TimeHelper.getFreshFormattedTime()}\n\nYou are Siya's coding assistant. ALWAYS produce complete, working code immediately; do not ask clarifying questions first (state your assumptions in one line after the code). Put every file in its own fenced markdown block with the language and relative file path in the fence info string (e.g., ```html:index.html, ```css:style.css, ```js:script.js, or ```python:main.py). ALWAYS include the filename after the language separated by a colon. For 'HTML code do' produce one complete single-file HTML page (```html:index.html) with inline CSS and JS. Keep prose short. Explain in the user's language (Hindi/Hinglish when the user writes it)."
            } else {
                "${PromptGenerator.GENDER_IDENTITY_BLOCK}\n\nCurrent local time: ${TimeHelper.getFreshFormattedTime()}\n\nYou are Siya Ai, a helpful, intelligent, and articulate female AI assistant. Examine all attached files, images, or documents carefully, understand the user's request, and provide a clear, accurate, and direct response in text. Answer in the user's language (Hindi/Hinglish when the user writes it)."
            }

            // Keep the last 6 turns as text context
            val history = (getApplication<Application>() as JarvisApp).chatRepository.turns.value.takeLast(6)
            val contents = mutableListOf<RestContent>()
            for (turn in history) {
                if (turn.userTranscript.isNotBlank() && turn.userTranscript != "(Attachment)") {
                    contents.add(RestContent(role = "user", parts = listOf(RestPart(text = turn.userTranscript))))
                }
                if (turn.jarvisResponse.isNotBlank() && turn.jarvisResponse != "Siya Ai is typing...") {
                    contents.add(RestContent(role = "model", parts = listOf(RestPart(text = turn.jarvisResponse))))
                }
            }

            val currentParts = mutableListOf<RestPart>()
            for (att in attachments) {
                val part = AttachmentProcessor.attachmentToRestPart(att)
                if (part != null) {
                    currentParts.add(part)
                }
            }
            if (cleanText.isNotBlank()) {
                currentParts.add(RestPart(text = cleanText))
            }
            contents.add(RestContent(role = "user", parts = currentParts))

            val hasMediaOrPdf = attachments.any { it.type == "image" || it.mime == "application/pdf" || it.name.endsWith(".pdf", ignoreCase = true) }
            val modelsToTry = if (hasMediaOrPdf) {
                listOf(GeminiConstants.VISION_MODEL, GeminiConstants.FALLBACK_TEXT_MODEL)
            } else {
                GeminiConstants.CODE_MODELS
            }

            var lastUiUpdateTime = 0L
            var latestAccumulatedText = ""

            val streamResult = restClient.streamGenerateContent(
                apiKey = apiKey,
                systemInstruction = systemInstruction,
                contents = contents,
                models = modelsToTry,
                onChunk = { accumulated, _ ->
                    latestAccumulatedText = accumulated
                    val now = System.currentTimeMillis()
                    // Throttle live UI updates to ~12 per second (~80ms)
                    if (now - lastUiUpdateTime >= 80L) {
                        lastUiUpdateTime = now
                        _pendingTurn.value = ChatTurn(
                            userTranscript = cleanText.ifEmpty { "(Attachment)" },
                            jarvisResponse = accumulated,
                            attachments = attachments
                        )
                    }
                }
            )

            _pendingTurn.value = null
            _isRestRunning.value = false

            streamResult.onSuccess { res ->
                val responseText = res.text.ifEmpty { "(No response received)" }
                (getApplication<Application>() as JarvisApp).chatRepository.addTurn(
                    userText = cleanText,
                    jarvisText = responseText,
                    attachments = attachments,
                    isMaxTokens = res.isMaxTokens
                )
            }.onFailure { err ->
                val friendlyError = err.message ?: "Internet ya server me problem aayi. Dobara try kijiye."
                val responseText = if (latestAccumulatedText.isNotBlank()) {
                    "$latestAccumulatedText\n\n[$friendlyError]"
                } else {
                    friendlyError
                }
                (getApplication<Application>() as JarvisApp).chatRepository.addTurn(
                    userText = cleanText,
                    jarvisText = responseText,
                    attachments = attachments,
                    isError = true
                )
            }
        }
    }

    fun generateImageMessage(
        prompt: String,
        aspectRatio: String = preferences.imageAspectRatio,
        stylePreset: String = preferences.imageStylePreset,
        negativePrompt: String = preferences.imageNegativePrompt
    ) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return

        val apiKey = preferences.apiKey.trim()

        viewModelScope.launch {
            _isRestRunning.value = true
            _pendingTurn.value = ChatTurn(
                userTranscript = cleanPrompt,
                jarvisResponse = "Creating image...",
                generatedImages = null
            )

            val client = com.jarvis.assistant.network.ImageGenClient()
            val result = client.generateImage(
                context = getApplication<Application>(),
                apiKey = apiKey,
                prompt = cleanPrompt,
                aspectRatio = aspectRatio,
                stylePreset = stylePreset,
                negativePrompt = negativePrompt
            )

            _pendingTurn.value = null
            _isRestRunning.value = false

            when (result) {
                is com.jarvis.assistant.network.ImageGenResult.Success -> {
                    (getApplication<Application>() as JarvisApp).chatRepository.addTurn(
                        userText = cleanPrompt,
                        jarvisText = "Generated image:",
                        generatedImages = listOf(result.imageFile.absolutePath)
                    )
                }
                is com.jarvis.assistant.network.ImageGenResult.Failure -> {
                    (getApplication<Application>() as JarvisApp).chatRepository.addTurn(
                        userText = cleanPrompt,
                        jarvisText = result.reason,
                        isError = true
                    )
                }
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(getApplication<Application>(), JarvisConversationService::class.java)
            .setAction(action)
        ContextCompat.startForegroundService(getApplication<Application>(), intent)
    }

    override fun onCleared() {
        super.onCleared()
        timeClockJob?.cancel()
        // Deliberately NOT stopping JarvisConversationService here. The
        // Activity/ViewModel being torn down (e.g. the app swiped away from
        // Recents) must not stop the mic or the live conversation - that's
        // the whole point of owning them in the service instead of here.
    }
}
