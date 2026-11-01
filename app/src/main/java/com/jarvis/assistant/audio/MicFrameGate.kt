package com.jarvis.assistant.audio

/**
 * Pure decision logic for microphone PCM frame gating in JarvisConversationService.
 *
 * External media playback (e.g., YouTube, Spotify, system music) MUST NEVER drop mic frames.
 * The AudioRecord hardware uses VOICE_COMMUNICATION with built-in hardware acoustic echo cancellation.
 */
object MicFrameGate {

    const val DEFAULT_ECHO_TAIL_GUARD_MS = 150L

    /**
     * Determines whether an incoming microphone frame should be discarded.
     *
     * @param ownPlaybackActive true if Siya's own AudioTrack or speaking state is currently active
     * @param msSinceOwnPlaybackEnd milliseconds elapsed since Siya's own audio playback finished
     * @param echoTailGuardMs duration in ms after own playback finishes during which residual acoustic reverberation is suppressed
     * @param isBargeInTriggered true if voice barge-in (e.g. over headset) has already been triggered to interrupt Siya
     * @return true if the frame should be dropped; false if the frame should proceed to VAD and live processing
     */
    fun shouldDropMicFrame(
        ownPlaybackActive: Boolean,
        msSinceOwnPlaybackEnd: Long,
        echoTailGuardMs: Long = DEFAULT_ECHO_TAIL_GUARD_MS,
        isBargeInTriggered: Boolean = false
    ): Boolean {
        if (isBargeInTriggered) {
            return false
        }
        if (ownPlaybackActive) {
            return true
        }
        if (msSinceOwnPlaybackEnd in 0 until echoTailGuardMs) {
            return true
        }
        return false
    }
}
