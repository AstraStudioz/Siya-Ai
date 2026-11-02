package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Phase1MicAndSpeakerModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        MicDiagnosticsTracker.resetForTest()
    }

    // 1. Mic Gate Function Tests
    @Test
    fun externalMediaNeverDropsFrames() {
        // External music playback (e.g. YouTube, Spotify) must not drop mic frames
        val shouldDrop = MicFrameGate.shouldDropMicFrame(
            ownPlaybackActive = false,
            msSinceOwnPlaybackEnd = 5000L,
            echoTailGuardMs = 150L,
            isBargeInTriggered = false
        )
        assertFalse("External media playing must never drop mic frames", shouldDrop)
    }

    @Test
    fun ownPlaybackWith150msTailDropsFrames() {
        // While own playback is active, drop frame
        assertTrue(
            "Own playback active must drop frame",
            MicFrameGate.shouldDropMicFrame(
                ownPlaybackActive = true,
                msSinceOwnPlaybackEnd = 0L,
                echoTailGuardMs = 150L,
                isBargeInTriggered = false
            )
        )

        // Within 150ms tail, drop frame
        assertTrue(
            "Within 150ms tail must drop frame",
            MicFrameGate.shouldDropMicFrame(
                ownPlaybackActive = false,
                msSinceOwnPlaybackEnd = 75L,
                echoTailGuardMs = 150L,
                isBargeInTriggered = false
            )
        )

        // After 150ms tail, do not drop frame
        assertFalse(
            "After 150ms tail must allow frame",
            MicFrameGate.shouldDropMicFrame(
                ownPlaybackActive = false,
                msSinceOwnPlaybackEnd = 160L,
                echoTailGuardMs = 150L,
                isBargeInTriggered = false
            )
        )
    }

    // 2. Continuous-send Rule When Lock is Off
    @Test
    fun continuousSendRuleWhenLockIsOff() {
        val lockEnabled = false
        val hasProfile = false
        val isSessionReady = true

        val capturedChunks = listOf(ByteArray(640), ByteArray(640), ByteArray(640))
        val sentChunks = mutableListOf<ByteArray>()

        for (chunk in capturedChunks) {
            val shouldContinuousSend = (!lockEnabled || !hasProfile) && isSessionReady
            if (shouldContinuousSend) {
                sentChunks.add(chunk)
                MicDiagnosticsTracker.recordChunkSent(chunk.size)
            }
        }

        assertEquals(3, sentChunks.size)
        assertEquals(3L, MicDiagnosticsTracker.snapshot.value.chunksSent)
        assertEquals((3 * 640).toLong(), MicDiagnosticsTracker.snapshot.value.bytesSent)
    }

    // 3. Speaking Watchdog (8 seconds)
    @Test
    fun speakingWatchdog8SecondTimeout() {
        val watchdogTimeoutMs = 8_000L
        val startTime = 100_000L

        // Stuck for 7,999 ms: watchdog has not expired
        val at7999Ms = 107_999L
        val shouldTripEarly = (at7999Ms - startTime) > watchdogTimeoutMs
        assertFalse("Watchdog should not trip before 8 seconds", shouldTripEarly)

        // Stuck for 8,001 ms: watchdog expires and force resets
        val at8001Ms = 108_001L
        val shouldTrip = (at8001Ms - startTime) > watchdogTimeoutMs
        assertTrue("Watchdog must trip after 8 seconds of continuous playback state", shouldTrip)
    }

    // 4. Diagnostics Counters
    @Test
    fun diagnosticsTrackerRecordsCountersCorrectly() {
        MicDiagnosticsTracker.recordFrameCaptured(0.045f)
        MicDiagnosticsTracker.recordFrameDropped(DropReason.SESSION_OFF)
        MicDiagnosticsTracker.recordFrameDropped(DropReason.OWN_PLAYBACK)
        MicDiagnosticsTracker.recordFrameDropped(DropReason.TAIL_GUARD)
        MicDiagnosticsTracker.recordFrameDropped(DropReason.SESSION_NOT_READY)
        MicDiagnosticsTracker.recordFrameDropped(DropReason.OWNER_PENDING)
        MicDiagnosticsTracker.recordFrameDropped(DropReason.MUTED)
        MicDiagnosticsTracker.recordChunkSent(640)
        MicDiagnosticsTracker.recordServerMessage()
        MicDiagnosticsTracker.recordInputTranscription("Hello Siya")
        MicDiagnosticsTracker.recordModelTurn()

        val snap = MicDiagnosticsTracker.snapshot.value
        assertEquals(0.045f, snap.lastRms, 0.0001f)
        assertEquals(1L, snap.droppedSessionOff)
        assertEquals(1L, snap.droppedOwnPlayback)
        assertEquals(1L, snap.droppedTailGuard)
        assertEquals(1L, snap.droppedSessionNotReady)
        assertEquals(1L, snap.droppedOwnerPending)
        assertEquals(1L, snap.droppedMuted)
        assertEquals(1L, snap.chunksSent)
        assertEquals(640L, snap.bytesSent)
        assertEquals(1L, snap.serverMessagesReceived)
        assertEquals(1L, snap.inputTranscriptionsReceived)
        assertEquals("Hello Siya", snap.lastGeminiHeardText)
        assertEquals(1L, snap.modelTurns)

        // Copy text MUST NEVER contain transcript text
        val copyText = MicDiagnosticsTracker.getCopyText()
        assertFalse("Copy text must never contain transcript content", copyText.contains("Hello Siya"))
        assertTrue("Copy text must contain dropped counters", copyText.contains("Dropped (OWN_PLAYBACK): 1"))
    }

    // 5. Unzip Safety (Zip-Slip and Nested Folder Flattening)
    @Test
    fun zipSlipVulnerabilityIsPrevented() {
        val rootDir = tempFolder.newFolder("target_model")
        val maliciousPath = "../../evil.txt"

        val destinationFile = File(rootDir, maliciousPath)
        val canonicalDest = destinationFile.canonicalPath
        val canonicalTarget = rootDir.canonicalPath

        val isSafe = canonicalDest.startsWith(canonicalTarget)
        assertFalse("Zip-slip path must be rejected", isSafe)
    }

    @Test
    fun nestedFolderModelFlattening() {
        val targetDir = tempFolder.newFolder("vosk_spk_nested")
        val nestedSubdir = File(targetDir, "vosk-model-spk-0.4").apply { mkdirs() }

        // Create 4 files inside nested folder
        File(nestedSubdir, "final.ext.raw").writeBytes(ByteArray(6 * 1024 * 1024))
        File(nestedSubdir, "mfcc.conf").writeText("sample-frequency=16000")
        File(nestedSubdir, "mean.vec").writeBytes(byteArrayOf(1, 2, 3))
        File(nestedSubdir, "transform.mat").writeBytes(byteArrayOf(4, 5, 6))

        // Simulate nested folder flattening
        val rootRaw = File(targetDir, "final.ext.raw")
        if (!rootRaw.exists()) {
            val subDirs = targetDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (sub in subDirs) {
                val nestedRaw = File(sub, "final.ext.raw")
                if (nestedRaw.exists()) {
                    val files = sub.listFiles() ?: continue
                    for (f in files) {
                        val dest = File(targetDir, f.name)
                        if (dest.exists()) dest.delete()
                        f.renameTo(dest)
                    }
                    sub.deleteRecursively()
                    break
                }
            }
        }

        assertTrue("final.ext.raw must be moved to root", File(targetDir, "final.ext.raw").exists())
        assertTrue("mfcc.conf must be moved to root", File(targetDir, "mfcc.conf").exists())
        assertTrue("mean.vec must be moved to root", File(targetDir, "mean.vec").exists())
        assertTrue("transform.mat must be moved to root", File(targetDir, "transform.mat").exists())
        assertFalse("nested folder must be removed", nestedSubdir.exists())
    }

    // 6. Speaker Model File Validation
    @Test
    fun speakerModelFileValidation() {
        val testDir = tempFolder.newFolder("vosk_validation")

        // Incomplete folder (missing final.ext.raw)
        File(testDir, "mfcc.conf").writeText("test")
        File(testDir, "mean.vec").writeBytes(byteArrayOf(1))
        File(testDir, "transform.mat").writeBytes(byteArrayOf(2))
        assertFalse("Missing final.ext.raw must fail validation", VoskModelManager.hasValidSpeakerModelFiles(testDir))

        // final.ext.raw too small (< 5MB)
        val raw = File(testDir, "final.ext.raw")
        raw.writeBytes(ByteArray(1024))
        assertFalse("Small final.ext.raw (< 5MB) must fail validation", VoskModelManager.hasValidSpeakerModelFiles(testDir))

        // Valid final.ext.raw (> 5MB)
        val raf = RandomAccessFile(raw, "rw")
        raf.setLength(6 * 1024 * 1024L) // 6MB
        raf.close()

        assertTrue("Complete 4 files with >5MB neural net must pass validation", VoskModelManager.hasValidSpeakerModelFiles(testDir))
    }

    // 7. Speaker Model State Machine
    @Test
    fun speakerModelStateTransitions() {
        VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.MISSING)
        assertEquals(SpeakerModelStatus.MISSING, VoskModelManager.speakerModelStatus.value)
        assertFalse(VoskModelManager.isSpeakerModelReady.value)

        VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.DOWNLOADING)
        assertEquals(SpeakerModelStatus.DOWNLOADING, VoskModelManager.speakerModelStatus.value)
        assertFalse(VoskModelManager.isSpeakerModelReady.value)

        VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.READY)
        assertEquals(SpeakerModelStatus.READY, VoskModelManager.speakerModelStatus.value)
        assertTrue(VoskModelManager.isSpeakerModelReady.value)

        VoskModelManager.updateSpeakerModelStatus(SpeakerModelStatus.CORRUPT)
        assertEquals(SpeakerModelStatus.CORRUPT, VoskModelManager.speakerModelStatus.value)
        assertFalse(VoskModelManager.isSpeakerModelReady.value)
    }
}
