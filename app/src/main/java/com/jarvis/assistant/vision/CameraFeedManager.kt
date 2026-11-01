package com.jarvis.assistant.vision

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraFeedManager private constructor() {

    companion object {
        @Volatile
        private var instance: CameraFeedManager? = null

        fun getInstance(): CameraFeedManager {
            return instance ?: synchronized(this) {
                instance ?: CameraFeedManager().also { instance = it }
            }
        }
    }

    private val _isCameraSharing = MutableStateFlow(false)
    val isCameraSharing: StateFlow<Boolean> = _isCameraSharing.asStateFlow()

    fun start() {
        if (ScreenCaptureManager.getInstance().isSharing.value) {
            JarvisConversationService.stopScreenCapture()
            try {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(JarvisApp.instance, "Screen share stopped for camera", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
                // Ignore if Looper is not mocked during tests
            }
        }
        _isCameraSharing.value = true
    }

    fun stop() {
        _isCameraSharing.value = false
    }
}
