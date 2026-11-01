package com.jarvis.assistant.ui.home

import com.jarvis.assistant.data.model.ConversationState

data class StatusPillUi(
    val statusText: String,
    val hintText: String,
    val colorHex: String,
    val orbKey: String
)

object StatusPillResolver {
    fun resolve(
        isSessionOn: Boolean,
        isMuted: Boolean,
        isStandby: Boolean,
        conversationState: ConversationState,
        connectionStatus: String,
        isMicSilent: Boolean = false,
        micSilentReason: String = ""
    ): StatusPillUi {
        if (!isSessionOn) {
            return StatusPillUi("MIC OFF (POWERED DOWN)", "Hold mic to power on", "#64748B", "idle")
        }
        if (isMuted) {
            return StatusPillUi("MIC MUTED (OFF) • TAP TO TALK", "Microphone is muted • Tap mic to unmute", "#EF4444", "idle")
        }
        if (isStandby) {
            return StatusPillUi("STANDBY • SAY 'HEY SIYA' OR TAP", "Listening for wake word • Tap mic to speak", "#3B82F6", "idle")
        }

        if (isMicSilent) {
            val hint = if (micSilentReason.isNotBlank()) "$micSilentReason • Tap to retry" else "Mic silenced by system • Tap to retry"
            return StatusPillUi("MIC SILENT", hint, "#EF4444", "idle")
        }

        // Session is ON. Check real connection status first.
        // Never show READY before setupComplete ("LIVE").
        val cleanStatus = connectionStatus.trim()
        if (cleanStatus != "LIVE") {
            return when {
                cleanStatus.startsWith("CONNECTING") ->
                    StatusPillUi("CONNECTING...", "Connecting to Gemini Live...", "#F59E0B", "idle")
                cleanStatus.startsWith("CONFIGURING") ->
                    StatusPillUi("CONFIGURING...", "Configuring neural session...", "#3B82F6", "idle")
                cleanStatus.startsWith("RECONNECTING") ->
                    StatusPillUi(cleanStatus, "Re-establishing connection...", "#F59E0B", "idle")
                cleanStatus.startsWith("ERROR") || cleanStatus.startsWith("SETUP REJECTED") || cleanStatus.startsWith("RECONNECT FAILED") ->
                    StatusPillUi(cleanStatus, "Connection issue • Check Settings", "#EF4444", "idle")
                cleanStatus.equals("OFFLINE", ignoreCase = true) ->
                    StatusPillUi("OFFLINE", "Disconnected • Tap mic to connect", "#9CA3AF", "idle")
                else ->
                    StatusPillUi(cleanStatus, "Connecting to Siya Ai...", "#F59E0B", "idle")
            }
        }

        // Connection is verified LIVE (setupComplete received)
        return when (conversationState) {
            ConversationState.LISTENING ->
                StatusPillUi("MIC ON • LISTENING...", "Speak now • Tap to mute • Hold to power off", "#10B981", "listening")
            ConversationState.SPEAKING ->
                StatusPillUi("SIYA AI SPEAKING...", "Tap mic to interrupt", "#06B6D4", "speaking")
            ConversationState.THINKING ->
                StatusPillUi("THINKING...", "Processing request...", "#F59E0B", "thinking")
            ConversationState.IDLE ->
                StatusPillUi("MIC ON • READY", "Tap to mute • Hold to power off", "#10B981", "idle")
        }
    }
}
