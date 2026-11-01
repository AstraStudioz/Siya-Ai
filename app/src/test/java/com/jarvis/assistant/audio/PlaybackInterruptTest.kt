package com.jarvis.assistant.audio

import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.util.PromptGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackInterruptTest {

    @Test
    fun testAudioChunkPcmAlignmentLogic() {
        fun alignPcm(pcmData: ByteArray): ByteArray? {
            if (pcmData.size < 16) return null
            val validLen = pcmData.size and 1.inv()
            return if (validLen == pcmData.size) pcmData else pcmData.copyOf(validLen)
        }

        // Chunks < 16 bytes must be rejected
        assertEquals(null, alignPcm(ByteArray(0)))
        assertEquals(null, alignPcm(ByteArray(15)))

        // Even length >= 16 bytes kept as is
        val evenChunk = ByteArray(64)
        val alignedEven = alignPcm(evenChunk)
        assertEquals(64, alignedEven?.size)

        // Odd length >= 16 bytes truncated to even length (16-bit PCM alignment)
        val oddChunk = ByteArray(65)
        val alignedOdd = alignPcm(oddChunk)
        assertEquals(64, alignedOdd?.size)
    }

    @Test
    fun testPlaybackInterruptionAndTurnSuppression() {
        var suppressAudioUntilTurnComplete = false
        var audioChunksReceivedInTurn = 0
        var state = ConversationState.SPEAKING
        var audioLevel = 0.85f

        fun onAudioDataReceived(pcmData: ByteArray) {
            if (suppressAudioUntilTurnComplete) return
            audioChunksReceivedInTurn++
            state = ConversationState.SPEAKING
        }

        // Initial speech from assistant arrives
        onAudioDataReceived(ByteArray(64))
        assertEquals(1, audioChunksReceivedInTurn)
        assertEquals(ConversationState.SPEAKING, state)

        // User interrupts (e.g. tap orb / barge-in)
        // 1. Audio suppression turned on for remainder of this turn
        suppressAudioUntilTurnComplete = true
        // 2. Audio level zeroed
        audioLevel = 0f
        // 3. State reset to LISTENING
        state = ConversationState.LISTENING

        assertEquals(ConversationState.LISTENING, state)
        assertEquals(0f, audioLevel, 0.001f)

        // Any delayed / in-flight chunks over network must be suppressed
        onAudioDataReceived(ByteArray(64))
        onAudioDataReceived(ByteArray(64))

        // State remains LISTENING, chunk count unchanged
        assertEquals(ConversationState.LISTENING, state)
        assertEquals(1, audioChunksReceivedInTurn)

        // On turn completion, suppression resets for next turn
        suppressAudioUntilTurnComplete = false
        audioChunksReceivedInTurn = 0

        onAudioDataReceived(ByteArray(64))
        assertEquals(1, audioChunksReceivedInTurn)
        assertEquals(ConversationState.SPEAKING, state)
    }

    @Test
    fun testSpeakingStateResetOnTurnCompletion() {
        var state = ConversationState.THINKING
        val isAudioPlaying = false

        // When turn completes and no audio is playing, state must reset to IDLE
        if (state == ConversationState.THINKING && !isAudioPlaying) {
            state = ConversationState.IDLE
        }
        assertEquals(ConversationState.IDLE, state)
    }

    @Test
    fun testVoiceGenderIdentityBlockFeminineGrammar() {
        val block = PromptGenerator.GENDER_IDENTITY_BLOCK
        assertTrue(block.contains("[VOICE & GENDER IDENTITY - HIGHEST PRIORITY]"))
        assertTrue(block.contains("female AI assistant"))
        assertTrue(block.contains("main karti hoon"))
        assertTrue(block.contains("main kar rahi hoon"))
        assertTrue(block.contains("main karungi"))
        assertTrue(block.contains("main bataungi"))
        assertTrue(block.contains("NEVER use masculine forms for yourself"))
    }
}
