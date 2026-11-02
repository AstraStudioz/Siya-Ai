package com.jarvis.assistant.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class DropReason {
    SESSION_OFF,
    OWN_PLAYBACK,
    TAIL_GUARD,
    SESSION_NOT_READY,
    OWNER_PENDING,
    MUTED
}

data class MicDiagnosticsSnapshot(
    val framesCapturedPerSec: Int = 0,
    val lastRms: Float = 0f,
    val droppedSessionOff: Long = 0,
    val droppedOwnPlayback: Long = 0,
    val droppedTailGuard: Long = 0,
    val droppedSessionNotReady: Long = 0,
    val droppedOwnerPending: Long = 0,
    val droppedMuted: Long = 0,
    val chunksSent: Long = 0,
    val bytesSent: Long = 0,
    val serverMessagesReceived: Long = 0,
    val inputTranscriptionsReceived: Long = 0,
    val lastGeminiHeardText: String = "",
    val modelTurns: Long = 0
)

object MicDiagnosticsTracker {
    private val framesInCurrentSecond = AtomicInteger(0)
    @Volatile
    private var lastSecondResetTime = System.currentTimeMillis()
    @Volatile
    private var currentFps = 0

    private val droppedSessionOffCount = AtomicLong(0)
    private val droppedOwnPlaybackCount = AtomicLong(0)
    private val droppedTailGuardCount = AtomicLong(0)
    private val droppedSessionNotReadyCount = AtomicLong(0)
    private val droppedOwnerPendingCount = AtomicLong(0)
    private val droppedMutedCount = AtomicLong(0)

    private val chunksSentCount = AtomicLong(0)
    private val bytesSentCount = AtomicLong(0)
    private val serverMessagesCount = AtomicLong(0)
    private val inputTranscriptionsCount = AtomicLong(0)
    private val modelTurnsCount = AtomicLong(0)

    @Volatile
    private var lastHeardText: String = ""

    private val _snapshot = MutableStateFlow(MicDiagnosticsSnapshot())
    val snapshot: StateFlow<MicDiagnosticsSnapshot> = _snapshot.asStateFlow()

    fun recordFrameCaptured(rms: Float) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastSecondResetTime >= 1000L) {
                currentFps = framesInCurrentSecond.getAndSet(1)
                lastSecondResetTime = now
            } else {
                framesInCurrentSecond.incrementAndGet()
            }
        }
        updateSnapshot(fps = currentFps, rms = rms)
    }

    fun recordFrameDropped(reason: DropReason) {
        when (reason) {
            DropReason.SESSION_OFF -> droppedSessionOffCount.incrementAndGet()
            DropReason.OWN_PLAYBACK -> droppedOwnPlaybackCount.incrementAndGet()
            DropReason.TAIL_GUARD -> droppedTailGuardCount.incrementAndGet()
            DropReason.SESSION_NOT_READY -> droppedSessionNotReadyCount.incrementAndGet()
            DropReason.OWNER_PENDING -> droppedOwnerPendingCount.incrementAndGet()
            DropReason.MUTED -> droppedMutedCount.incrementAndGet()
        }
        updateSnapshot()
    }

    fun recordChunkSent(bytes: Int) {
        chunksSentCount.incrementAndGet()
        bytesSentCount.addAndGet(bytes.toLong())
        updateSnapshot()
    }

    fun recordServerMessage() {
        serverMessagesCount.incrementAndGet()
        updateSnapshot()
    }

    fun recordInputTranscription(text: String) {
        inputTranscriptionsCount.incrementAndGet()
        // Kept in memory exclusively for on-screen diagnostics UI; never logged
        lastHeardText = text
        updateSnapshot()
    }

    fun recordModelTurn() {
        modelTurnsCount.incrementAndGet()
        updateSnapshot()
    }

    private fun updateSnapshot(
        fps: Int = currentFps,
        rms: Float = _snapshot.value.lastRms
    ) {
        _snapshot.value = MicDiagnosticsSnapshot(
            framesCapturedPerSec = fps,
            lastRms = rms,
            droppedSessionOff = droppedSessionOffCount.get(),
            droppedOwnPlayback = droppedOwnPlaybackCount.get(),
            droppedTailGuard = droppedTailGuardCount.get(),
            droppedSessionNotReady = droppedSessionNotReadyCount.get(),
            droppedOwnerPending = droppedOwnerPendingCount.get(),
            droppedMuted = droppedMutedCount.get(),
            chunksSent = chunksSentCount.get(),
            bytesSent = bytesSentCount.get(),
            serverMessagesReceived = serverMessagesCount.get(),
            inputTranscriptionsReceived = inputTranscriptionsCount.get(),
            lastGeminiHeardText = lastHeardText,
            modelTurns = modelTurnsCount.get()
        )
    }

    /**
     * Formats strictly numeric telemetry without any transcript text.
     */
    fun getCopyText(): String {
        val s = _snapshot.value
        return """
            Frames captured/sec: ${s.framesCapturedPerSec}
            Last RMS: ${"%.4f".format(s.lastRms)}
            Dropped (SESSION_OFF): ${s.droppedSessionOff}
            Dropped (OWN_PLAYBACK): ${s.droppedOwnPlayback}
            Dropped (TAIL_GUARD): ${s.droppedTailGuard}
            Dropped (SESSION_NOT_READY): ${s.droppedSessionNotReady}
            Dropped (OWNER_PENDING): ${s.droppedOwnerPending}
            Dropped (MUTED): ${s.droppedMuted}
            Chunks sent: ${s.chunksSent}
            Bytes sent: ${s.bytesSent}
            Server messages: ${s.serverMessagesReceived}
            Input transcriptions: ${s.inputTranscriptionsReceived}
            Model turns: ${s.modelTurns}
        """.trimIndent()
    }

    fun resetForTest() {
        framesInCurrentSecond.set(0)
        currentFps = 0
        lastSecondResetTime = System.currentTimeMillis()
        droppedSessionOffCount.set(0)
        droppedOwnPlaybackCount.set(0)
        droppedTailGuardCount.set(0)
        droppedSessionNotReadyCount.set(0)
        droppedOwnerPendingCount.set(0)
        droppedMutedCount.set(0)
        chunksSentCount.set(0)
        bytesSentCount.set(0)
        serverMessagesCount.set(0)
        inputTranscriptionsCount.set(0)
        modelTurnsCount.set(0)
        lastHeardText = ""
        updateSnapshot(fps = 0, rms = 0f)
    }
}
