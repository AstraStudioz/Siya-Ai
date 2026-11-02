package com.jarvis.assistant.audio

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileOutputStream

enum class SpeakerModelStatus {
    READY,
    DOWNLOADING,
    MISSING,
    CORRUPT
}

object VoskModelManager {
    private const val TAG = "VoskModelManager"

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isSpeakerModelReady = MutableStateFlow(false)
    val isSpeakerModelReady: StateFlow<Boolean> = _isSpeakerModelReady.asStateFlow()

    private val _speakerModelStatus = MutableStateFlow(SpeakerModelStatus.MISSING)
    val speakerModelStatus: StateFlow<SpeakerModelStatus> = _speakerModelStatus.asStateFlow()

    private val _isInitComplete = MutableStateFlow(false)
    val isInitComplete: StateFlow<Boolean> = _isInitComplete.asStateFlow()

    @Volatile
    var model: Model? = null
        private set

    @Volatile
    var speakerModel: SpeakerModel? = null
        private set

    val isModelAvailable: Boolean get() = model != null
    val isSpeakerModelAvailable: Boolean get() = speakerModel != null

    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val hasNotifiedSpeakerOffline = java.util.concurrent.atomic.AtomicBoolean(false)

    fun resetSessionNotification() {
        hasNotifiedSpeakerOffline.set(false)
    }

    fun shouldNotifySpeakerOffline(): Boolean {
        return !isSpeakerModelAvailable && hasNotifiedSpeakerOffline.compareAndSet(false, true)
    }

    fun hasValidModelFiles(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val amModel = File(dir, "am/final.mdl")
        val rootModel = File(dir, "final.mdl")
        return (amModel.exists() && amModel.length() > 0L) || (rootModel.exists() && rootModel.length() > 0L)
    }

    fun hasValidModelAssets(assetManager: AssetManager, path: String): Boolean {
        return try {
            val rootFiles = assetManager.list(path) ?: return false
            if ("final.mdl" in rootFiles) return true
            val amFiles = assetManager.list(if (path.isEmpty()) "am" else "$path/am") ?: emptyArray()
            "final.mdl" in amFiles
        } catch (_: Exception) {
            false
        }
    }

    fun hasValidSpeakerModelFiles(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val raw = File(dir, "final.ext.raw")
        val mfcc = File(dir, "mfcc.conf")
        val mean = File(dir, "mean.vec")
        val mat = File(dir, "transform.mat")
        val hasRawOrMdl = (raw.exists() && raw.length() > 5 * 1024 * 1024L) || (File(dir, "spk.mdl").exists() && File(dir, "spk.mdl").length() > 0L)
        return hasRawOrMdl && mfcc.exists() && mfcc.length() > 0L && mean.exists() && mean.length() > 0L && mat.exists() && mat.length() > 0L
    }

    fun updateSpeakerModelStatus(status: SpeakerModelStatus) {
        _speakerModelStatus.value = status
        _isSpeakerModelReady.value = (status == SpeakerModelStatus.READY)
    }

    fun reloadSpeakerModel(context: Context): Boolean {
        val spkTargetDir = File(context.filesDir, "vosk-spk-model")
        if (hasValidSpeakerModelFiles(spkTargetDir)) {
            return try {
                val loadedSpk = SpeakerModel(spkTargetDir.absolutePath)
                speakerModel = loadedSpk
                _isSpeakerModelReady.value = true
                _speakerModelStatus.value = SpeakerModelStatus.READY
                Log.i(TAG, "Vosk SpeakerModel reloaded successfully!")
                true
            } catch (e: Throwable) {
                speakerModel = null
                _isSpeakerModelReady.value = false
                _speakerModelStatus.value = SpeakerModelStatus.CORRUPT
                Log.e(TAG, "Failed loading Vosk SpeakerModel: ${e.message}", e)
                false
            }
        } else {
            speakerModel = null
            _isSpeakerModelReady.value = false
            _speakerModelStatus.value = SpeakerModelStatus.CORRUPT
            return false
        }
    }

    fun hasValidSpeakerModelAssets(assetManager: AssetManager, path: String): Boolean {
        return try {
            val files = assetManager.list(path) ?: return false
            "final.ext.raw" in files || "spk.mdl" in files
        } catch (_: Exception) {
            false
        }
    }

    fun init(context: Context, scope: CoroutineScope, force: Boolean = false) {
        if (!force && model != null && speakerModel != null) return
        if (!isInitializing.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Unpack & load speech recognition model
                val targetDir = File(context.filesDir, "vosk-model")
                val assetsHaveModel = hasValidModelAssets(context.assets, "model-en-us")
                val diskHasModel = hasValidModelFiles(targetDir)

                if (assetsHaveModel && !diskHasModel) {
                    Log.i(TAG, "Unpacking Vosk model to internal storage: ${targetDir.absolutePath}")
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    copyAssetFolder(context.assets, "model-en-us", targetDir)
                    Log.i(TAG, "Vosk model unpacked successfully.")
                }

                if (hasValidModelFiles(targetDir)) {
                    try {
                        val loadedModel = Model(targetDir.absolutePath)
                        model = loadedModel
                        _isModelReady.value = true
                        Log.i(TAG, "Vosk Model ready in memory!")
                    } catch (e: Throwable) {
                        model = null
                        _isModelReady.value = false
                        Log.w(TAG, "Could not initialize Vosk Model: ${e.message}")
                    }
                } else {
                    model = null
                    _isModelReady.value = false
                    Log.w(TAG, "Vosk speech model binary (final.mdl) not present in assets or internal storage. Offline wake-word recognition disabled.")
                }

                // 2. Unpack & load speaker recognition model (x-vector extractor)
                try {
                    val spkTargetDir = File(context.filesDir, "vosk-spk-model")
                    val assetsHaveSpk = hasValidSpeakerModelAssets(context.assets, "model-spk")
                    val diskHasSpk = hasValidSpeakerModelFiles(spkTargetDir)

                    if (assetsHaveSpk && !diskHasSpk) {
                        Log.i(TAG, "Unpacking Vosk Speaker model to: ${spkTargetDir.absolutePath}")
                        spkTargetDir.deleteRecursively()
                        spkTargetDir.mkdirs()
                        copyAssetFolder(context.assets, "model-spk", spkTargetDir)
                        Log.i(TAG, "Vosk Speaker model unpacked successfully.")
                    }

                    if (hasValidSpeakerModelFiles(spkTargetDir)) {
                        try {
                            val loadedSpk = SpeakerModel(spkTargetDir.absolutePath)
                            speakerModel = loadedSpk
                            _isSpeakerModelReady.value = true
                            _speakerModelStatus.value = SpeakerModelStatus.READY
                            Log.i(TAG, "Vosk SpeakerModel ready in memory!")
                        } catch (e: Throwable) {
                            speakerModel = null
                            _isSpeakerModelReady.value = false
                            _speakerModelStatus.value = SpeakerModelStatus.CORRUPT
                            Log.w(TAG, "Could not initialize Vosk SpeakerModel: ${e.message}")
                        }
                    } else {
                        speakerModel = null
                        _isSpeakerModelReady.value = false
                        val hasPartialFiles = spkTargetDir.exists() && (spkTargetDir.listFiles()?.isNotEmpty() == true)
                        _speakerModelStatus.value = if (hasPartialFiles || assetsHaveSpk) {
                            SpeakerModelStatus.CORRUPT
                        } else {
                            SpeakerModelStatus.MISSING
                        }
                        Log.w(TAG, "Vosk speaker model binary (final.ext.raw) not present. Status: ${_speakerModelStatus.value}")
                    }
                } catch (e: Throwable) {
                    _speakerModelStatus.value = SpeakerModelStatus.CORRUPT
                    Log.w(TAG, "Failed to load Vosk SpeakerModel: ${e.message}", e)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error in Vosk model manager init: ${e.message}", e)
            } finally {
                isInitializing.set(false)
                _isInitComplete.value = true
            }
        }
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toDir: File) {
        val list = assetManager.list(fromAssetPath)
        if (list.isNullOrEmpty()) {
            try {
                assetManager.open(fromAssetPath).use { input ->
                    toDir.parentFile?.mkdirs()
                    FileOutputStream(toDir).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy asset $fromAssetPath: ${e.message}")
            }
        } else {
            toDir.mkdirs()
            for (item in list) {
                val childAsset = if (fromAssetPath.isEmpty()) item else "$fromAssetPath/$item"
                val childTarget = File(toDir, item)
                copyAssetFolder(assetManager, childAsset, childTarget)
            }
        }
    }
}
