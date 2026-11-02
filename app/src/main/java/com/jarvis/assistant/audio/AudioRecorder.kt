package com.jarvis.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val context: android.content.Context? = null,
    private val onBluetoothSilentFallback: (() -> Unit)? = null,
    private val onAudioChunk: (chunk: ByteArray, amplitude: Float, frequencyData: AudioFrequencyData) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Ultra-low latency 20ms chunk: 16000 samples/sec * 0.02s * 2 bytes/sample = 640 bytes
        const val CHUNK_SIZE_BYTES = 640
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    val isMuted = AtomicBoolean(false)

    val isMicSilenced = kotlinx.coroutines.flow.MutableStateFlow(false)
    val micSilentReason = kotlinx.coroutines.flow.MutableStateFlow("")

    var sensitivityGain: Float = 1.0f

    var activeAudioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        private set

    private val _activeAudioSourceName = kotlinx.coroutines.flow.MutableStateFlow("VOICE_COMMUNICATION")
    val activeAudioSourceName: kotlinx.coroutines.flow.StateFlow<String> = _activeAudioSourceName

    private fun getSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "SOURCE_$source"
    }

    @SuppressLint("MissingPermission")
    private fun createRecordForSource(source: Int, bufferSize: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                source,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord init failed for ${getSourceName(source)}")
                record.release()
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception creating AudioRecord for ${getSourceName(source)}: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Unable to get valid min buffer size")
            return false
        }

        val bufferSize = maxOf(minBufferSize * 4, 32000)

        // Try primary source, with immediate fallback chain if initialization fails
        var currentSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        var record = createRecordForSource(currentSource, bufferSize)

        if (record == null) {
            Log.w(TAG, "VOICE_COMMUNICATION failed to initialize. Falling back to VOICE_RECOGNITION.")
            currentSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            record = createRecordForSource(currentSource, bufferSize)
        }

        if (record == null) {
            Log.w(TAG, "VOICE_RECOGNITION failed to initialize. Falling back to MIC.")
            currentSource = MediaRecorder.AudioSource.MIC
            record = createRecordForSource(currentSource, bufferSize)
        }

        if (record == null) {
            Log.e(TAG, "All mic audio sources failed to initialize.")
            return false
        }

        activeAudioSource = currentSource
        _activeAudioSourceName.value = getSourceName(currentSource)

        return try {
            val sessionId = record.audioSessionId
            if (currentSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                        Log.i(TAG, "Hardware AcousticEchoCanceler enabled on session $sessionId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable AcousticEchoCanceler: ${e.message}")
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                        Log.i(TAG, "Hardware NoiseSuppressor enabled on session $sessionId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable NoiseSuppressor: ${e.message}")
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        automaticGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                        Log.i(TAG, "Hardware AutomaticGainControl enabled on session $sessionId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable AutomaticGainControl: ${e.message}")
                    }
                }
            }

            record.startRecording()
            audioRecord = record
            isRecording.set(true)

            recordingJob = scope.launch(Dispatchers.IO) {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (_: Exception) {}

                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var currentRecord: AudioRecord? = record
                var framesChecked = 0
                var maxObservedAmp = 0f
                var hasAttemptedDampedFallback = false
                var zeroAmpStartTimeMs = 0L
                var lastLogTimeMs = 0L
                var consecutiveZeroReads = 0
                var recoveryBackoffMs = 500L

                while (isActive && isRecording.get()) {
                    var consecutiveErrors = 0
                    while (isActive && isRecording.get() && currentRecord != null) {
                        try {
                            val readBytes = currentRecord.read(buffer, 0, buffer.size)
                            val now = System.currentTimeMillis()

                            if (readBytes > 0) {
                                consecutiveErrors = 0
                                recoveryBackoffMs = 500L
                                val freq = AudioUtils.analyzeFrequency(buffer, readBytes, SAMPLE_RATE, sensitivityGain)
                                val currentAmp = freq.amplitude

                                // Track zero RMS / silent mic state for >= 3 seconds
                                if (currentAmp < 0.0005f && !isMuted.get()) {
                                    consecutiveZeroReads++
                                    if (zeroAmpStartTimeMs == 0L) {
                                        zeroAmpStartTimeMs = now
                                    } else if (now - zeroAmpStartTimeMs >= 3000L) {
                                        if (!isMicSilenced.value) {
                                            isMicSilenced.value = true
                                            micSilentReason.value = "Mic silenced by system / another app"
                                            Log.w("SiyaMic", "Mic RMS remained at 0 for 3 seconds. Marked MIC SILENT.")
                                            onBluetoothSilentFallback?.invoke()
                                        }
                                    }
                                } else {
                                    zeroAmpStartTimeMs = 0L
                                    consecutiveZeroReads = 0
                                    if (isMicSilenced.value) {
                                        isMicSilenced.value = false
                                        micSilentReason.value = ""
                                        Log.i("SiyaMic", "Mic sound detected (amp=$currentAmp). Restored from MIC SILENT.")
                                    }
                                }

                                // 1Hz "SiyaMic" diagnostic logging
                                if (now - lastLogTimeMs >= 1000L) {
                                    lastLogTimeMs = now
                                    val recState = currentRecord.state
                                    val recRecState = currentRecord.recordingState
                                    var clientSilenced = false
                                    var amMode = -1
                                    try {
                                        val am = context?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                                        amMode = am?.mode ?: -1
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && am != null) {
                                            val configs = am.activeRecordingConfigurations
                                            val activeCfg = configs.firstOrNull { it.clientAudioSessionId == currentRecord.audioSessionId }
                                            clientSilenced = activeCfg?.isClientSilenced ?: false
                                        }
                                    } catch (_: Throwable) {}

                                    Log.i("SiyaMic", "RMS=${"%.4f".format(currentAmp)} | State=$recRecState (init=$recState) | read=$readBytes | isClientSilenced=$clientSilenced | Mode=$amMode | Muted=${isMuted.get()} | Source=${getSourceName(activeAudioSource)}")
                                }

                                // Check for heavily suppressed/damped audio on VOICE_COMMUNICATION
                                if (!hasAttemptedDampedFallback && activeAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                                    framesChecked++
                                    if (currentAmp > maxObservedAmp) {
                                        maxObservedAmp = currentAmp
                                    }
                                    // If after 60 frames (~1.2s) amplitude is practically zero (< 0.003)
                                    if (framesChecked >= 60) {
                                        if (maxObservedAmp < 0.003f) {
                                            hasAttemptedDampedFallback = true
                                            Log.w(TAG, "Audio severely suppressed on VOICE_COMMUNICATION (maxAmp=$maxObservedAmp). Falling back to VOICE_RECOGNITION.")
                                            break // Break inner loop to switch source
                                        }
                                    }
                                }

                                if (!isMuted.get()) {
                                    val chunkCopy = buffer.copyOf(readBytes)
                                    onAudioChunk(chunkCopy, currentAmp, freq)
                                } else {
                                    onAudioChunk(ByteArray(0), 0f, AudioFrequencyData())
                                }
                            } else if (readBytes < 0) {
                                consecutiveErrors++
                                Log.w("SiyaMic", "AudioRecord read error code: $readBytes (error count: $consecutiveErrors)")
                                delay(40)
                                if (consecutiveErrors > 10 && isRecording.get()) {
                                    Log.e(TAG, "Consecutive AudioRecord read errors exceeded limit. Attempting auto-recovery...")
                                    isMicSilenced.value = true
                                    micSilentReason.value = "AudioRecord read error: $readBytes"
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SiyaMic", "Exception during AudioRecord read loop: ${e.message}", e)
                            delay(50)
                        }
                    }

                    // Auto-recovery / source fallback if broken or damped:
                    if (isActive && isRecording.get()) {
                        Log.i("SiyaMic", "Attempting AudioRecord recovery with backoff ${recoveryBackoffMs}ms...")
                        delay(recoveryBackoffMs)
                        recoveryBackoffMs = (recoveryBackoffMs * 2).coerceAtMost(4000L)
                        try {
                            currentRecord?.release()
                        } catch (_: Exception) {}

                        // If switching source due to damping
                        val nextSource = if (activeAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                            MediaRecorder.AudioSource.VOICE_RECOGNITION
                        } else if (activeAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION && maxObservedAmp < 0.003f) {
                            MediaRecorder.AudioSource.MIC
                        } else {
                            activeAudioSource
                        }

                        Log.i(TAG, "Re-initializing AudioRecord with source ${getSourceName(nextSource)}")
                        val newRecord = createRecordForSource(nextSource, bufferSize)
                        if (newRecord != null) {
                            try {
                                newRecord.startRecording()
                                currentRecord = newRecord
                                audioRecord = newRecord
                                activeAudioSource = nextSource
                                _activeAudioSourceName.value = getSourceName(nextSource)
                                framesChecked = 0
                                maxObservedAmp = 0f
                                Log.i("SiyaMic", "AudioRecord successfully restarted on ${getSourceName(nextSource)}!")
                            } catch (e: Exception) {
                                Log.e("SiyaMic", "Failed starting new record: ${e.message}")
                            }
                        } else {
                            delay(1000)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
            false
        }
    }

    fun stop() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects: ${e.message}")
        } finally {
            echoCanceler = null
            noiseSuppressor = null
            automaticGainControl = null
        }

        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }
}
