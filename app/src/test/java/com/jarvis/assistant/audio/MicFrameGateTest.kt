package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicFrameGateTest {

    @Test
    fun externalMediaPlayingNeverDropsMicFrames() {
        // When Siya is not speaking and no echo tail is active, frames must never be dropped,
        // even if external media (YouTube, Spotify, etc.) is playing in the system.
        val shouldDrop = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = false,
            msSinceOwnPlaybackEnd = 10_000L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = false
        )
        assertFalse("External media playing must never cause mic frames to be dropped", shouldDrop)
    }

    @Test
    fun ownPlaybackActiveDropsMicFrames() {
        // While Siya's own AudioTrack is actively outputting speech, frames are dropped to avoid self-hearing
        val shouldDrop = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = true,
            msSinceOwnPlaybackEnd = 0L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = false
        )
        assertTrue("Mic frame should be dropped while Siya's own audio is actively playing", shouldDrop)
    }

    @Test
    fun echoTailGuardSuppressesFramesWithinGuardDuration() {
        // Within 150ms of own playback ending, residual acoustic reverberation in the room is suppressed
        val shouldDropEarly = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = false,
            msSinceOwnPlaybackEnd = 50L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = false
        )
        val shouldDropBoundary = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = false,
            msSinceOwnPlaybackEnd = 149L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = false
        )
        assertTrue("Frames within 150ms echo guard must be dropped", shouldDropEarly)
        assertTrue("Frames at edge of echo guard must be dropped", shouldDropBoundary)
    }

    @Test
    fun framesAllowedAfterEchoTailGuardElapsed() {
        // Once 150ms have elapsed after playback ended, frames must be admitted immediately
        val shouldDrop = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = false,
            msSinceOwnPlaybackEnd = 150L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = false
        )
        val shouldDropLater = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = false,
            msSinceOwnPlaybackEnd = 500L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = false
        )
        assertFalse("Frames after echo guard duration must be admitted", shouldDrop)
        assertFalse("Frames long after playback ended must be admitted", shouldDropLater)
    }

    @Test
    fun bargeInTriggeredAllowsFramesEvenDuringOwnPlayback() {
        // When headset or user barge-in is triggered, mic frames must be admitted immediately to process interruption
        val shouldDropDuringPlayback = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = true,
            msSinceOwnPlaybackEnd = 0L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = true
        )
        val shouldDropDuringEchoTail = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = false,
            msSinceOwnPlaybackEnd = 30L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = true
        )
        assertFalse("Barge-in must allow mic frames even while own playback is active", shouldDropDuringPlayback)
        assertFalse("Barge-in must allow mic frames even during echo tail decay", shouldDropDuringEchoTail)
    }
}
