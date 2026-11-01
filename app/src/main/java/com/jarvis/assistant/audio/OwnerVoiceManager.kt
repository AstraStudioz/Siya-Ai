package com.jarvis.assistant.audio

import android.util.Log
import com.jarvis.assistant.data.preferences.AppPreferences
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.log10
import kotlin.math.sqrt

enum class VoiceMatchStatus {
    OWNER,
    NOT_OWNER,
    UNCERTAIN;

    val isOwner: Boolean get() = this == OWNER
    val isMatch: Boolean get() = this != NOT_OWNER

    companion object {
        // Backwards compatibility aliases
        val MATCH get() = OWNER
        val MISMATCH get() = NOT_OWNER
        val UNVERIFIABLE get() = UNCERTAIN
    }
}

data class QualityGateResult(
    val passed: Boolean,
    val reason: String,
    val voicedSeconds: Float,
    val snrDb: Float,
    val clippingPercent: Float
)

data class ConsistencyResult(
    val isConsistent: Boolean,
    val outlierIndex: Int?,
    val qualityLabel: String,
    val meanDistance: Float
)

data class VoiceMatchResult(
    val status: VoiceMatchStatus = VoiceMatchStatus.UNCERTAIN,
    val isMatch: Boolean = true,
    val distance: Float = 0.0f,
    val similarityPercent: Int = 100,
    val threshold: Float = OwnerVoiceManager.DEFAULT_THRESHOLD,
    val hasExtractedVector: Boolean = false,
    val reason: String = "",
    val centroidDistance: Float = 0.0f,
    val top3Distance: Float = 0.0f
)

/**
 * Realistic voice biometrics engine using Vosk/Kaldi 128-d x-vectors.
 * Provides quality gating, pairwise consistency checks, personal threshold calibration,
 * 3-state decisions (OWNER, NOT_OWNER, UNCERTAIN), and adaptive profile updating.
 */
object OwnerVoiceManager {
    private const val TAG = "OwnerVoiceManager"

    const val DEFAULT_THRESHOLD = 0.65f
    const val STRICT_THRESHOLD = 0.59f
    const val RELAXED_THRESHOLD = 0.71f
    const val STRICT_DELTA = -0.06f
    const val RELAXED_DELTA = 0.06f
    const val MIN_CALIBRATED_THRESHOLD = 0.50f
    const val MAX_CALIBRATED_THRESHOLD = 0.75f
    const val UNCERTAIN_BAND_WIDTH = 0.08f

    // Realistic disclaimer note required on enrollment & test screens
    const val REALISTIC_DISCLAIMER =
        "Voice matching reduces accidental use by other people. It is not a lock: a close relative or a recording can sometimes pass. Sensitive actions need extra confirmation."

    /**
     * Cosine distance: distance = 1.0 - (dot(v1, v2) / (norm(v1) * norm(v2)))
     * Range: [0.0, 2.0]. Lower values indicate identical vocal characteristics.
     */
    fun calculateCosineDistance(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 1.0f

        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in v1.indices) {
            val a = v1[i].toDouble()
            val b = v2[i].toDouble()
            dot += a * b
            norm1 += a * a
            norm2 += b * b
        }

        if (norm1 <= 0.0 || norm2 <= 0.0) return 1.0f

        val cosSim = (dot / (sqrt(norm1) * sqrt(norm2))).coerceIn(-1.0, 1.0)
        return (1.0 - cosSim).toFloat().coerceIn(0.0f, 2.0f)
    }

    /**
     * Maps distance to match strength percentage:
     * d <= T - 0.15 -> 100%
     * d == T -> 60%
     * d >= T + 0.08 -> 0%
     */
    fun distanceToMatchStrength(distance: Float, threshold: Float): Int {
        val tLow = threshold - 0.15f
        val tHigh = threshold + UNCERTAIN_BAND_WIDTH

        if (distance >= tHigh - 0.001f) return 0
        if (distance <= tLow + 0.001f) return 100
        if (distance <= threshold) {
            val ratio = ((distance - tLow) / (threshold - tLow).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            return kotlin.math.round(100f - ratio * 40f).toInt().coerceIn(60, 100)
        }
        val ratio = ((distance - threshold) / (tHigh - threshold).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        return kotlin.math.round(60f - ratio * 60f).toInt().coerceIn(0, 60)
    }

    /**
     * Computes normalized centroid of vectors.
     */
    fun averageVectors(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors[0].size
        val avg = FloatArray(dim)

        for (vec in vectors) {
            if (vec.size != dim) continue
            for (i in 0 until dim) {
                avg[i] += vec[i]
            }
        }

        val count = vectors.size.toFloat()
        for (i in 0 until dim) {
            avg[i] /= count
        }

        var norm = 0.0
        for (v in avg) {
            norm += v.toDouble() * v.toDouble()
        }
        val sqrtNorm = sqrt(norm).toFloat()
        if (sqrtNorm > 0f) {
            for (i in 0 until dim) {
                avg[i] /= sqrtNorm
            }
        }

        return avg
    }

    /**
     * Quality gate per sample:
     * - >= 3.0 s of voiced speech
     * - Estimated SNR >= 15 dB (voiced frames vs noise floor from first 300 ms)
     * - Clipping < 1% of samples (|sample| >= 32000)
     */
    fun checkQualityGate(pcmAudio: ByteArray, sampleRate: Int = 16000): QualityGateResult {
        val totalSamples = pcmAudio.size / 2
        if (totalSamples == 0) {
            return QualityGateResult(false, "too short", 0f, 0f, 0f)
        }

        // 1. Check clipping
        var clippedCount = 0
        val shortSamples = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val byteIndex = i * 2
            val low = pcmAudio[byteIndex].toInt() and 0xFF
            val high = pcmAudio[byteIndex + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            shortSamples[i] = sample
            if (sample >= 32000 || sample <= -32000) {
                clippedCount++
            }
        }

        val clippingPercent = (clippedCount.toFloat() / totalSamples.toFloat()) * 100f
        if (clippingPercent >= 1.0f) {
            return QualityGateResult(false, "audio clipped", 0f, 0f, clippingPercent)
        }

        // 2. Estimate noise floor from the first 300 ms (4800 samples at 16kHz)
        val noiseWindowSize = minOf(4800, totalSamples)
        var noiseSumSq = 0.0
        for (i in 0 until noiseWindowSize) {
            val s = shortSamples[i].toDouble()
            noiseSumSq += s * s
        }
        val noiseRms = sqrt(noiseSumSq / noiseWindowSize.coerceAtLeast(1))

        // 3. Frame-by-frame voiced speech detection (20ms frames = 320 samples)
        val frameSize = (sampleRate * 0.02).toInt().coerceAtLeast(160)
        val frameCount = totalSamples / frameSize
        val voicedThreshold = maxOf(noiseRms * 1.5, 300.0)

        var voicedSamples = 0
        var voicedSumSq = 0.0

        for (f in 0 until frameCount) {
            val start = f * frameSize
            var frameSq = 0.0
            for (i in 0 until frameSize) {
                val s = shortSamples[start + i].toDouble()
                frameSq += s * s
            }
            val frameRms = sqrt(frameSq / frameSize)
            if (frameRms >= voicedThreshold) {
                voicedSamples += frameSize
                voicedSumSq += frameSq
            }
        }

        val voicedSeconds = voicedSamples.toFloat() / sampleRate.toFloat()
        if (voicedSeconds < 3.0f) {
            return QualityGateResult(false, "too short", voicedSeconds, 0f, clippingPercent)
        }

        val voicedRms = sqrt(voicedSumSq / voicedSamples.coerceAtLeast(1))
        if (voicedRms < 400.0) {
            return QualityGateResult(false, "too quiet", voicedSeconds, 0f, clippingPercent)
        }

        val snrDb = (20.0 * log10(voicedRms / maxOf(noiseRms, 1.0))).toFloat()
        if (snrDb < 15.0f) {
            return QualityGateResult(false, "too noisy", voicedSeconds, snrDb, clippingPercent)
        }

        return QualityGateResult(true, "ok", voicedSeconds, snrDb, clippingPercent)
    }

    /**
     * Consistency check:
     * After sample 3, compute pairwise cosine distances.
     * Reject a sample if its mean distance to others > mean + 2.5 * sigma or > 0.55.
     * Voice profile quality label (Good < 0.35, Fair 0.35..0.48, Weak > 0.48).
     */
    fun checkConsistency(samples: List<FloatArray>): ConsistencyResult {
        if (samples.size < 3) {
            return ConsistencyResult(isConsistent = true, outlierIndex = null, qualityLabel = "Fair", meanDistance = 0.40f)
        }

        val n = samples.size
        val pairwiseDistances = mutableListOf<Float>()
        val sampleMeanDistances = FloatArray(n)

        for (i in 0 until n) {
            var sumDist = 0.0f
            var count = 0
            for (j in 0 until n) {
                if (i != j) {
                    val d = calculateCosineDistance(samples[i], samples[j])
                    sumDist += d
                    count++
                    if (i < j) pairwiseDistances.add(d)
                }
            }
            sampleMeanDistances[i] = if (count > 0) sumDist / count else 0.5f
        }

        val overallMean = pairwiseDistances.average().toFloat()
        var variance = 0.0
        for (d in pairwiseDistances) {
            val diff = d - overallMean
            variance += diff * diff
        }
        val sigma = sqrt(variance / pairwiseDistances.size.coerceAtLeast(1)).toFloat()

        // Check for outlier
        val outlierThreshold = minOf(overallMean + (2.5f * sigma), 0.55f)
        var outlierIndex: Int? = null

        for (i in 0 until n) {
            if (sampleMeanDistances[i] > outlierThreshold) {
                outlierIndex = i
                break
            }
        }

        val qualityLabel = when {
            overallMean < 0.35f -> "Good"
            overallMean <= 0.48f -> "Fair"
            else -> "Weak"
        }

        return ConsistencyResult(
            isConsistent = outlierIndex == null,
            outlierIndex = outlierIndex,
            qualityLabel = qualityLabel,
            meanDistance = overallMean
        )
    }

    /**
     * Personal threshold calibration from enrolled samples:
     * Computes genuine distance mean and standard deviation.
     * T = clamp(mean + 3 * sigma, 0.50, 0.75).
     */
    fun calibrateThreshold(samples: List<FloatArray>): Float {
        if (samples.size < 2) return DEFAULT_THRESHOLD

        val distances = mutableListOf<Float>()
        for (i in samples.indices) {
            for (j in (i + 1) until samples.size) {
                distances.add(calculateCosineDistance(samples[i], samples[j]))
            }
        }

        if (distances.isEmpty()) return DEFAULT_THRESHOLD

        val mean = distances.average().toFloat()
        var variance = 0.0
        for (d in distances) {
            val diff = d - mean
            variance += diff * diff
        }
        val sigma = sqrt(variance / distances.size).toFloat()

        val calibratedT = mean + (3.0f * sigma)
        return calibratedT.coerceIn(MIN_CALIBRATED_THRESHOLD, MAX_CALIBRATED_THRESHOLD)
    }

    /**
     * Extracts a speaker embedding vector (x-vector) from a 16kHz mono 16-bit PCM audio buffer.
     */
    @Synchronized
    fun extractSpeakerVector(pcmAudio: ByteArray): FloatArray? {
        val model = VoskModelManager.model
        val spkModel = VoskModelManager.speakerModel

        if (model == null || spkModel == null) {
            Log.w(TAG, "Cannot extract speaker vector: Vosk model or speaker model is not ready")
            return null
        }
        if (pcmAudio.size < 3200) {
            Log.w(TAG, "Audio buffer too short for speaker extraction (${pcmAudio.size} bytes)")
            return null
        }

        var rec: Recognizer? = null
        return try {
            rec = Recognizer(model, 16000.0f, spkModel)

            var offset = 0
            val chunkSize = 4096
            while (offset < pcmAudio.size) {
                val len = minOf(chunkSize, pcmAudio.size - offset)
                rec.acceptWaveForm(pcmAudio.copyOfRange(offset, offset + len), len)
                offset += len
            }

            // Silence padding to complete temporal pooling
            val silencePadding = ByteArray(12800)
            rec.acceptWaveForm(silencePadding, silencePadding.size)

            val jsonStr = rec.finalResult
            if (jsonStr.isNullOrBlank()) return null

            val json = JSONObject(jsonStr)
            if (json.has("spk")) {
                val arr = json.getJSONArray("spk")
                val vector = FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
                if (vector.isNotEmpty()) vector else null
            } else {
                Log.d(TAG, "No 'spk' field found in Vosk final result: $jsonStr")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error extracting speaker vector from PCM: ${e.message}", e)
            null
        } finally {
            try {
                rec?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Scoring v2:
     * d = 0.5 * d_centroid + 0.5 * d_top3
     * Decision:
     * d <= T -> OWNER
     * d >= T + 0.08 -> NOT_OWNER
     * otherwise -> UNCERTAIN
     */
    fun scoreVerification(
        currentVector: FloatArray,
        centroid: FloatArray?,
        enrolledSamples: List<FloatArray>,
        threshold: Float
    ): VoiceMatchResult {
        if ((centroid == null || centroid.isEmpty()) && enrolledSamples.isEmpty()) {
            return VoiceMatchResult(
                status = VoiceMatchStatus.UNCERTAIN,
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = threshold,
                hasExtractedVector = true,
                reason = "No enrolled owner voice profile"
            )
        }

        val dCentroid = if (centroid != null && centroid.isNotEmpty()) {
            calculateCosineDistance(currentVector, centroid)
        } else {
            0.5f
        }

        val sampleDistances = enrolledSamples.map { calculateCosineDistance(currentVector, it) }.sorted()
        val top3 = if (sampleDistances.isNotEmpty()) {
            sampleDistances.take(minOf(3, sampleDistances.size))
        } else {
            listOf(dCentroid)
        }
        val dTop3 = top3.average().toFloat()

        val finalDistance = (0.5f * dCentroid) + (0.5f * dTop3)
        val strength = distanceToMatchStrength(finalDistance, threshold)

        val status = when {
            finalDistance <= threshold -> VoiceMatchStatus.OWNER
            finalDistance >= (threshold + UNCERTAIN_BAND_WIDTH) -> VoiceMatchStatus.NOT_OWNER
            else -> VoiceMatchStatus.UNCERTAIN
        }

        val reason = when (status) {
            VoiceMatchStatus.OWNER -> "Verified owner (d=%.3f <= T=%.2f)".format(finalDistance, threshold)
            VoiceMatchStatus.NOT_OWNER -> "Voice rejected: stranger detected (d=%.3f >= T+0.08=%.2f)".format(finalDistance, threshold + UNCERTAIN_BAND_WIDTH)
            VoiceMatchStatus.UNCERTAIN -> "Uncertain match in borderline zone (d=%.3f, T=%.2f)".format(finalDistance, threshold)
        }

        return VoiceMatchResult(
            status = status,
            isMatch = (status != VoiceMatchStatus.NOT_OWNER),
            distance = finalDistance,
            similarityPercent = strength,
            threshold = threshold,
            hasExtractedVector = true,
            reason = reason,
            centroidDistance = dCentroid,
            top3Distance = dTop3
        )
    }

    /**
     * Verifies an extracted speaker vector against the enrolled owner profile.
     */
    fun verifySpeakerVector(currentVector: FloatArray, prefs: AppPreferences): VoiceMatchResult {
        val centroid = prefs.getOwnerVoiceProfile()
        val enrolledSamples = prefs.getOwnerVoiceSamples()
        val threshold = prefs.ownerVoiceThreshold
        return scoreVerification(currentVector, centroid, enrolledSamples, threshold)
    }

    /**
     * Verifies raw PCM audio against the enrolled owner profile.
     * Enforces model readiness check and accumulated voiced duration (>= 1.5s).
     */
    fun verifySpeakerAudio(
        pcmAudio: ByteArray,
        prefs: AppPreferences,
        voicedDurationSeconds: Float = -1f
    ): VoiceMatchResult {
        if (!prefs.isOwnerVoiceEnabled || !prefs.hasOwnerVoiceProfile()) {
            return VoiceMatchResult(
                status = VoiceMatchStatus.OWNER,
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false,
                reason = "Owner voice gating disabled or no profile enrolled"
            )
        }

        if (VoskModelManager.speakerModelStatus.value != SpeakerModelStatus.READY) {
            Log.w(TAG, "Speaker model not ready (${VoskModelManager.speakerModelStatus.value}). Returning UNCERTAIN without blocking.")
            return VoiceMatchResult(
                status = VoiceMatchStatus.UNCERTAIN,
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false,
                reason = "Speaker model is not READY"
            )
        }

        // Must have at least 1.5s voiced audio for a confident decision
        if (voicedDurationSeconds in 0.0f..1.49f) {
            Log.w(TAG, "Voiced speech too short (${voicedDurationSeconds}s < 1.5s). Returning UNCERTAIN.")
            return VoiceMatchResult(
                status = VoiceMatchStatus.UNCERTAIN,
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false,
                reason = "Speech duration under 1.5s"
            )
        }

        // 1.5s of 16kHz 16-bit mono PCM is 48000 bytes
        if (pcmAudio.size < 48000) {
            Log.w(TAG, "Audio buffer under 1.5s (${pcmAudio.size} bytes). Returning UNCERTAIN.")
            return VoiceMatchResult(
                status = VoiceMatchStatus.UNCERTAIN,
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false,
                reason = "Audio buffer under 1.5s"
            )
        }

        val vector = extractSpeakerVector(pcmAudio)
        if (vector == null) {
            Log.w(TAG, "Could not extract speaker vector. Returning UNCERTAIN.")
            return VoiceMatchResult(
                status = VoiceMatchStatus.UNCERTAIN,
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false,
                reason = "Insufficient features for speaker extraction"
            )
        }

        val centroid = prefs.getOwnerVoiceProfile()
        val enrolledSamples = prefs.getOwnerVoiceSamples()
        val threshold = prefs.ownerVoiceThreshold

        val result = scoreVerification(vector, centroid, enrolledSamples, threshold)

        // Adaptive profile: on OWNER with d <= T - 0.10, update adaptive ring buffer
        if (result.status == VoiceMatchStatus.OWNER && result.distance <= (threshold - 0.10f)) {
            try {
                prefs.voiceStorage.addAdaptiveSample(vector)
            } catch (e: Exception) {
                Log.w(TAG, "Could not add adaptive sample: ${e.message}")
            }
        }

        return result
    }
}
