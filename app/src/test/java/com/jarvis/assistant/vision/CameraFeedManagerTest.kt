package com.jarvis.assistant.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraFeedManagerTest {

    private lateinit var cameraFeedManager: CameraFeedManager
    private lateinit var screenCaptureManager: ScreenCaptureManager

    @Before
    fun setUp() {
        cameraFeedManager = CameraFeedManager.getInstance()
        screenCaptureManager = ScreenCaptureManager.getInstance()
        cameraFeedManager.stop()
        screenCaptureManager.setSharingForTesting(false)
    }

    @Test
    fun testInitialStateIsFalse() {
        assertFalse(cameraFeedManager.isCameraSharing.value)
    }

    @Test
    fun testStartAndStopFlipState() {
        assertFalse(cameraFeedManager.isCameraSharing.value)

        cameraFeedManager.start()
        assertTrue(cameraFeedManager.isCameraSharing.value)

        cameraFeedManager.stop()
        assertFalse(cameraFeedManager.isCameraSharing.value)
    }

    @Test
    fun testStartingCameraStopsScreenShare() {
        // Simulate active screen share
        screenCaptureManager.setSharingForTesting(true)
        assertTrue(screenCaptureManager.isSharing.value)

        // Starting camera must stop screen share
        cameraFeedManager.start()

        // Verify camera sharing is now true
        assertTrue(cameraFeedManager.isCameraSharing.value)
        // Verify screen share was stopped
        assertFalse(screenCaptureManager.isSharing.value)
    }
}
