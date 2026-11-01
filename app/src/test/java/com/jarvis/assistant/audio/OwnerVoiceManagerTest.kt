package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class OwnerVoiceManagerTest {

    @Test
    fun testCosineDistance_identicalVectors_returnsZero() {
        val v1 = floatArrayOf(1.0f, 2.0f, 3.0f)
        val v2 = floatArrayOf(1.0f, 2.0f, 3.0f)
        val distance = OwnerVoiceManager.calculateCosineDistance(v1, v2)
        assertEquals(0.0f, distance, 0.001f)
    }

    @Test
    fun testCosineDistance_orthogonalVectors_returnsOne() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val distance = OwnerVoiceManager.calculateCosineDistance(v1, v2)
        assertEquals(1.0f, distance, 0.001f)
    }

    @Test
    fun testCosineDistance_oppositeVectors_returnsTwo() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(-1.0f, 0.0f, 0.0f)
        val distance = OwnerVoiceManager.calculateCosineDistance(v1, v2)
        assertEquals(2.0f, distance, 0.001f)
    }

    @Test
    fun testAverageVectors_computesNormalizedCentroid() {
        val v1 = floatArrayOf(1.0f, 0.0f)
        val v2 = floatArrayOf(0.0f, 1.0f)
        val avg = OwnerVoiceManager.averageVectors(listOf(v1, v2))
        assertNotNull(avg)
        val norm = sqrt((avg!![0] * avg[0] + avg[1] * avg[1]).toDouble()).toFloat()
        assertEquals(1.0f, norm, 0.001f)
    }

    @Test
    fun testScoring_combinedCentroidAndTop3() {
        // Enrolled vectors (5 samples)
        val s1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val s2 = floatArrayOf(0.98f, 0.1f, 0.0f)
        val s3 = floatArrayOf(0.95f, 0.15f, 0.0f)
        val s4 = floatArrayOf(0.90f, 0.20f, 0.0f)
        val s5 = floatArrayOf(0.85f, 0.25f, 0.0f)

        val centroid = OwnerVoiceManager.averageVectors(listOf(s1, s2, s3, s4, s5))
        assertNotNull(centroid)

        val current = floatArrayOf(0.99f, 0.05f, 0.0f)
        val threshold = 0.65f

        val result = OwnerVoiceManager.scoreVerification(
            currentVector = current,
            centroid = centroid,
            enrolledSamples = listOf(s1, s2, s3, s4, s5),
            threshold = threshold
        )

        // d = 0.5 * d_centroid + 0.5 * d_top3
        val expectedD = (0.5f * result.centroidDistance) + (0.5f * result.top3Distance)
        assertEquals(expectedD, result.distance, 0.001f)
        assertEquals(VoiceMatchStatus.OWNER, result.status)
        assertTrue("Owner verdict must have isMatch = true", result.isMatch)
        assertTrue("Match strength should be >= 60% for owner", result.similarityPercent >= 60)
    }

    @Test
    fun testBoundaryDecisions_owner_uncertain_notOwner() {
        val dummyVector = floatArrayOf(1.0f, 0.0f)
        val centroid = floatArrayOf(1.0f, 0.0f)
        val enrolled = listOf(floatArrayOf(1.0f, 0.0f))
        val threshold = 0.60f

        // 1. Owner: d <= T
        val ownerRes = OwnerVoiceManager.scoreVerification(
            currentVector = floatArrayOf(0.99f, 0.05f),
            centroid = centroid,
            enrolledSamples = enrolled,
            threshold = threshold
        )
        assertEquals(VoiceMatchStatus.OWNER, ownerRes.status)
        assertTrue(ownerRes.isMatch)

        // 2. Not Owner: d >= T + 0.08 (distance >= 0.68)
        val strangerRes = OwnerVoiceManager.scoreVerification(
            currentVector = floatArrayOf(-1.0f, 0.0f), // distance = 2.0
            centroid = centroid,
            enrolledSamples = enrolled,
            threshold = threshold
        )
        assertEquals(VoiceMatchStatus.NOT_OWNER, strangerRes.status)
        assertFalse("Stranger must have isMatch = false", strangerRes.isMatch)
        assertEquals(0, strangerRes.similarityPercent)

        // 3. Uncertain: T < d < T + 0.08
        // Vector with angle ~ 65 degrees (cos ~ 0.38, distance ~ 0.62)
        val borderlineVector = floatArrayOf(0.38f, 0.925f)
        val uncertainRes = OwnerVoiceManager.scoreVerification(
            currentVector = borderlineVector,
            centroid = centroid,
            enrolledSamples = enrolled,
            threshold = threshold
        )
        assertEquals(VoiceMatchStatus.UNCERTAIN, uncertainRes.status)
        assertTrue("UNCERTAIN must have isMatch = true so chat responses are allowed", uncertainRes.isMatch)
    }

    @Test
    fun testDistanceToMatchStrength_piecewiseLinear() {
        val threshold = 0.65f
        // d <= T - 0.15 (0.50) -> 100%
        assertEquals(100, OwnerVoiceManager.distanceToMatchStrength(0.50f, threshold))
        assertEquals(100, OwnerVoiceManager.distanceToMatchStrength(0.20f, threshold))

        // d == T (0.65) -> 60%
        assertEquals(60, OwnerVoiceManager.distanceToMatchStrength(0.65f, threshold))

        // d >= T + 0.08 (0.73) -> 0%
        assertEquals(0, OwnerVoiceManager.distanceToMatchStrength(0.73f, threshold))
        assertEquals(0, OwnerVoiceManager.distanceToMatchStrength(1.10f, threshold))
    }

    @Test
    fun testCalibration_thresholdClamping() {
        // Highly identical samples -> small mean + 3*sigma -> clamps to 0.50
        val tightSamples = List(5) { floatArrayOf(1.0f, 0.01f * it) }
        val tMin = OwnerVoiceManager.calibrateThreshold(tightSamples)
        assertEquals(0.50f, tMin, 0.001f)

        // Very distant samples -> large mean + 3*sigma -> clamps to 0.75
        val wideSamples = listOf(
            floatArrayOf(1.0f, 0.0f),
            floatArrayOf(0.0f, 1.0f),
            floatArrayOf(-1.0f, 0.0f),
            floatArrayOf(0.5f, 0.5f),
            floatArrayOf(-0.5f, -0.5f)
        )
        val tMax = OwnerVoiceManager.calibrateThreshold(wideSamples)
        assertEquals(0.75f, tMax, 0.001f)
    }

    @Test
    fun testConsistency_outlierRejection() {
        // 4 identical vectors, 1 completely orthogonal outlier
        val normal1 = floatArrayOf(1.0f, 0.1f, 0.0f)
        val normal2 = floatArrayOf(1.0f, 0.12f, 0.0f)
        val normal3 = floatArrayOf(1.0f, 0.08f, 0.0f)
        val normal4 = floatArrayOf(1.0f, 0.11f, 0.0f)
        val outlier = floatArrayOf(0.0f, 0.0f, 1.0f) // distance = 1.0 to all

        val samplesWithOutlier = listOf(normal1, normal2, normal3, normal4, outlier)
        val consistency = OwnerVoiceManager.checkConsistency(samplesWithOutlier)

        assertFalse("Outlier should be detected as inconsistent", consistency.isConsistent)
        assertEquals(4, consistency.outlierIndex)

        // Without outlier
        val consistentSamples = listOf(normal1, normal2, normal3, normal4)
        val cleanConsistency = OwnerVoiceManager.checkConsistency(consistentSamples)
        assertTrue("Clean samples should be consistent", cleanConsistency.isConsistent)
        assertNull(cleanConsistency.outlierIndex)
        assertEquals("Good", cleanConsistency.qualityLabel)
    }

    @Test
    fun testQualityGate_syntheticPcm() {
        // 1. Short audio (< 3s = < 96000 bytes for 16kHz mono 16-bit)
        val shortPcm = ByteArray(32000) // 1 second
        val shortRes = OwnerVoiceManager.checkQualityGate(shortPcm)
        assertFalse(shortRes.passed)
        assertEquals("too short", shortRes.reason)

        // 2. Clipped audio (>= 1% at 32000)
        val sampleCount = 16000 * 4 // 4 seconds = 64000 samples
        val clippedPcm = ByteArray(sampleCount * 2)
        val clippedVal: Short = 32500
        for (i in 0 until 1000) { // ~1.5% clipped
            clippedPcm[i * 2] = (clippedVal.toInt() and 0xFF).toByte()
            clippedPcm[i * 2 + 1] = ((clippedVal.toInt() shr 8) and 0xFF).toByte()
        }
        val clippedRes = OwnerVoiceManager.checkQualityGate(clippedPcm)
        assertFalse(clippedRes.passed)
        assertEquals("audio clipped", clippedRes.reason)

        // 3. Clean simulated 4-second speech with low noise floor and strong voiced frames
        val cleanPcm = ByteArray(sampleCount * 2)
        // First 300ms (4800 samples) = low ambient noise (sample amp ~ 50)
        val noiseVal: Short = 50
        for (i in 0 until 4800) {
            cleanPcm[i * 2] = (noiseVal.toInt() and 0xFF).toByte()
            cleanPcm[i * 2 + 1] = ((noiseVal.toInt() shr 8) and 0xFF).toByte()
        }
        // Remaining 3.7 seconds = clear speech (sample amp ~ 2000)
        val speechVal: Short = 2000
        for (i in 4800 until sampleCount) {
            cleanPcm[i * 2] = (speechVal.toInt() and 0xFF).toByte()
            cleanPcm[i * 2 + 1] = ((speechVal.toInt() shr 8) and 0xFF).toByte()
        }
        val cleanRes = OwnerVoiceManager.checkQualityGate(cleanPcm)
        assertTrue("Clean synthetic PCM should pass quality gate", cleanRes.passed)
        assertEquals("ok", cleanRes.reason)
        assertTrue("Voiced duration should be >= 3.0s", cleanRes.voicedSeconds >= 3.0f)
        assertTrue("SNR should be >= 15 dB", cleanRes.snrDb >= 15.0f)
    }

    @Test
    fun testVoiceMatchResult_backwardCompatibility() {
        val ownerResult = VoiceMatchResult(
            status = VoiceMatchStatus.OWNER,
            isMatch = true,
            distance = 0.2f
        )
        assertTrue(ownerResult.status.isOwner)
        assertTrue(ownerResult.isMatch)
        assertEquals(VoiceMatchStatus.MATCH, ownerResult.status)

        val mismatchResult = VoiceMatchResult(
            status = VoiceMatchStatus.NOT_OWNER,
            isMatch = false,
            distance = 0.95f
        )
        assertFalse(mismatchResult.status.isOwner)
        assertFalse(mismatchResult.isMatch)
        assertEquals(VoiceMatchStatus.MISMATCH, mismatchResult.status)

        val uncertainResult = VoiceMatchResult(
            status = VoiceMatchStatus.UNCERTAIN,
            isMatch = true,
            distance = 0.68f
        )
        assertFalse(uncertainResult.status.isOwner)
        assertTrue(uncertainResult.isMatch)
        assertEquals(VoiceMatchStatus.UNVERIFIABLE, uncertainResult.status)
    }
}
