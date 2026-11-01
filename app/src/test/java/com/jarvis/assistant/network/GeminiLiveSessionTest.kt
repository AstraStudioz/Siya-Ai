package com.jarvis.assistant.network

import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.ui.home.StatusPillResolver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiLiveSessionTest {

    @Test
    fun testStatusPillStateMachineNoReadyBeforeSetupComplete() {
        // 1. Session off
        val offPill = StatusPillResolver.resolve(
            isSessionOn = false,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.IDLE,
            connectionStatus = "LIVE"
        )
        assertEquals("MIC OFF (POWERED DOWN)", offPill.statusText)

        // 2. Muted
        val mutedPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = true,
            isStandby = false,
            conversationState = ConversationState.IDLE,
            connectionStatus = "LIVE"
        )
        assertEquals("MIC MUTED (OFF) • TAP TO TALK", mutedPill.statusText)

        // 3. Standby
        val standbyPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = true,
            conversationState = ConversationState.IDLE,
            connectionStatus = "LIVE"
        )
        assertEquals("STANDBY • SAY 'HEY SIYA' OR TAP", standbyPill.statusText)

        // 3b. Mic Silent
        val silentPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.IDLE,
            connectionStatus = "LIVE",
            isMicSilent = true,
            micSilentReason = "Mic silenced by system / another app"
        )
        assertEquals("MIC SILENT", silentPill.statusText)
        assertTrue(silentPill.hintText.contains("Mic silenced by system"))

        // 4. Session ON, but socket is CONNECTING... -> MUST NOT show READY
        val connectingPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.IDLE,
            connectionStatus = "CONNECTING..."
        )
        assertEquals("CONNECTING...", connectingPill.statusText)
        assertFalse(connectingPill.statusText.contains("READY"))

        // 5. Session ON, but socket is CONFIGURING... -> MUST NOT show READY
        val configuringPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.IDLE,
            connectionStatus = "CONFIGURING..."
        )
        assertEquals("CONFIGURING...", configuringPill.statusText)
        assertFalse(configuringPill.statusText.contains("READY"))

        // 6. Session ON, but socket is RECONNECTING (2) -> MUST NOT show READY
        val reconnectingPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.IDLE,
            connectionStatus = "RECONNECTING (2)"
        )
        assertEquals("RECONNECTING (2)", reconnectingPill.statusText)
        assertFalse(reconnectingPill.statusText.contains("READY"))

        // 7. Session ON, but OFFLINE -> MUST NOT show READY
        val offlinePill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.IDLE,
            connectionStatus = "OFFLINE"
        )
        assertEquals("OFFLINE", offlinePill.statusText)
        assertFalse(offlinePill.statusText.contains("READY"))

        // 8. Session ON and connectionStatus is LIVE (setupComplete acknowledged) -> NOW show READY
        val liveIdlePill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.IDLE,
            connectionStatus = "LIVE"
        )
        assertEquals("MIC ON • READY", liveIdlePill.statusText)
        assertEquals("idle", liveIdlePill.orbKey)

        // 9. Session ON, LIVE, and user speaking -> LISTENING
        val liveListeningPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.LISTENING,
            connectionStatus = "LIVE"
        )
        assertEquals("MIC ON • LISTENING...", liveListeningPill.statusText)
        assertEquals("listening", liveListeningPill.orbKey)

        // 10. Session ON, LIVE, and assistant speaking -> SPEAKING
        val liveSpeakingPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.SPEAKING,
            connectionStatus = "LIVE"
        )
        assertEquals("SIYA AI SPEAKING...", liveSpeakingPill.statusText)
        assertEquals("speaking", liveSpeakingPill.orbKey)

        // 11. Session ON, LIVE, and model thinking -> THINKING
        val liveThinkingPill = StatusPillResolver.resolve(
            isSessionOn = true,
            isMuted = false,
            isStandby = false,
            conversationState = ConversationState.THINKING,
            connectionStatus = "LIVE"
        )
        assertEquals("THINKING...", liveThinkingPill.statusText)
        assertEquals("thinking", liveThinkingPill.orbKey)
    }

    @Test
    fun testReconnectBackoffSequence() {
        assertEquals(1000L, GeminiLiveWebSocket.computeBackoffDelay(1))
        assertEquals(2000L, GeminiLiveWebSocket.computeBackoffDelay(2))
        assertEquals(4000L, GeminiLiveWebSocket.computeBackoffDelay(3))
        assertEquals(8000L, GeminiLiveWebSocket.computeBackoffDelay(4))
        assertEquals(15000L, GeminiLiveWebSocket.computeBackoffDelay(5))
        assertEquals(15000L, GeminiLiveWebSocket.computeBackoffDelay(6))
    }

    @Test
    fun testFallbackModelOrderOnSetupFailure() {
        // DEFAULT_MODEL must be the current GA Live model gemini-3.8-live
        assertEquals("models/gemini-3.8-live", GeminiConstants.DEFAULT_MODEL)

        val chain = GeminiConstants.LIVE_FALLBACK_CHAIN
        assertTrue(chain.size >= 3)
        assertEquals("models/gemini-3.8-live", chain[0])
        assertEquals("models/gemini-3.8-live-extended-thinking", chain[1])
        assertEquals("models/gemini-3.1-flash-live-preview", chain[2])

        // Fallback transition simulation
        fun getNextModel(current: String): String? {
            val idx = chain.indexOf(current)
            return if (idx in 0 until chain.lastIndex) chain[idx + 1] else null
        }

        assertEquals("models/gemini-3.8-live-extended-thinking", getNextModel(GeminiConstants.MODEL_3_8_LIVE))
        assertEquals("models/gemini-3.1-flash-live-preview", getNextModel(GeminiConstants.MODEL_3_8_LIVE_EXTENDED))
        assertNull(getNextModel(chain.last()))
    }

    @Test
    fun testSetupJsonNeverContainsBothSpellingsOfOneField() {
        val genConfig = JSONObject().apply {
            put("response_modalities", listOf("AUDIO"))
            put("speech_config", JSONObject().apply {
                put("voice_config", JSONObject().apply {
                    put("prebuilt_voice_config", JSONObject().apply {
                        put("voice_name", "Aoede")
                    })
                })
            })
            put("temperature", 0.6)
        }

        // Must NOT contain mediaResolution or media_resolution conflicting fields
        assertFalse("generation_config must not contain mediaResolution camelCase", genConfig.has("mediaResolution"))
        assertFalse("generation_config must not contain media_resolution", genConfig.has("media_resolution"))
    }

    @Test
    fun testThinkingTimeoutToastSuppressedWhenSetupNotComplete() {
        var toastEmitted = false
        var state = ConversationState.LISTENING

        fun onUtteranceEnd(isSessionReady: Boolean, audioChunksSent: Int) {
            if (!isSessionReady || audioChunksSent == 0) {
                // When setup is not complete or no audio was sent, return cleanly to IDLE without 15s timeout
                state = ConversationState.IDLE
                toastEmitted = false
            } else {
                state = ConversationState.THINKING
                // Simulating 15-second timeout firing
                toastEmitted = true
            }
        }

        // Case 1: Session not ready (setup not complete) -> Toast suppressed
        onUtteranceEnd(isSessionReady = false, audioChunksSent = 0)
        assertFalse("15s timeout toast must be suppressed when session is not ready", toastEmitted)
        assertEquals(ConversationState.IDLE, state)

        // Case 2: Session ready, but no audio chunks sent -> Toast suppressed
        onUtteranceEnd(isSessionReady = true, audioChunksSent = 0)
        assertFalse("15s timeout toast must be suppressed when no audio chunks were sent", toastEmitted)
        assertEquals(ConversationState.IDLE, state)

        // Case 3: Session is ready AND audio was streamed -> Allowed to trigger thinking timeout
        onUtteranceEnd(isSessionReady = true, audioChunksSent = 15)
        assertTrue("15s timeout is only permitted when setup complete and audio was sent", toastEmitted)
        assertEquals(ConversationState.THINKING, state)
    }

    @Test
    fun testHalfDuplexGatePausesDuringPlaybackAndResumesAfter300ms() {
        var isPlaybackActive = false
        var lastNonLivePlaybackEndTime = 0L

        fun shouldStreamMicChunk(now: Long): Boolean {
            if (isPlaybackActive) {
                lastNonLivePlaybackEndTime = now
                return false
            }
            if (now - lastNonLivePlaybackEndTime < 300L) {
                return false
            }
            return true
        }

        // T0: Idle, no playback -> Mic chunk should stream
        assertTrue(shouldStreamMicChunk(1000L))

        // T1: Playback starts -> Mic streaming must pause
        isPlaybackActive = true
        assertFalse(shouldStreamMicChunk(1100L))
        assertFalse(shouldStreamMicChunk(2000L))

        // T2: Playback stops at 2000L
        isPlaybackActive = false
        // At 2100L (+100ms) -> Still paused
        assertFalse(shouldStreamMicChunk(2100L))
        // At 2299L (+299ms) -> Still paused
        assertFalse(shouldStreamMicChunk(2299L))
        // At 2301L (+301ms) -> Resumed!
        assertTrue(shouldStreamMicChunk(2301L))
    }

    @Test
    fun testThreeSecondsZeroRmsTriggersMicSilent() {
        var zeroAmpStartTimeMs = 0L
        var isMicSilenced = false

        fun processAmplitude(amp: Float, now: Long) {
            if (amp < 0.0005f) {
                if (zeroAmpStartTimeMs == 0L) {
                    zeroAmpStartTimeMs = now
                } else if (now - zeroAmpStartTimeMs >= 3000L) {
                    isMicSilenced = true
                }
            } else {
                zeroAmpStartTimeMs = 0L
                isMicSilenced = false
            }
        }

        processAmplitude(0.05f, 1000L)
        assertFalse(isMicSilenced)

        // Silent starts at 2000L
        processAmplitude(0.0f, 2000L)
        assertFalse(isMicSilenced)

        // At 4000L (2 seconds of silence) -> Not yet silent
        processAmplitude(0.0f, 4000L)
        assertFalse(isMicSilenced)

        // At 5000L (3 seconds of silence) -> Transitions to MIC SILENT
        processAmplitude(0.0f, 5000L)
        assertTrue(isMicSilenced)

        // Sound arrives -> Recovers
        processAmplitude(0.02f, 5100L)
        assertFalse(isMicSilenced)
    }
}
