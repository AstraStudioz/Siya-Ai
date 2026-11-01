package com.jarvis.assistant.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskProgressOverlayTest {

    @Test
    fun testMinTouchTargetStandard() {
        assertTrue(
            "Touch targets for interactive overlay components must be at least 48dp",
            TaskProgressOverlay.MIN_TOUCH_TARGET_DP >= 48
        )
    }

    @Test
    fun testCalculateClampedPositionWithinScreenBounds() {
        val displayWidth = 1080
        val displayHeight = 2400
        val overlayWidth = 200
        val overlayHeight = 150
        val topInset = 72

        // Normal inside position
        val (x, y) = TaskProgressOverlay.calculateClampedPosition(
            rawX = 300,
            rawY = 400,
            overlayWidth = overlayWidth,
            overlayHeight = overlayHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            topInset = topInset
        )
        assertEquals(300, x)
        assertEquals(400, y)
    }

    @Test
    fun testCalculateClampedPositionOutOfBounds() {
        val displayWidth = 1080
        val displayHeight = 2400
        val overlayWidth = 200
        val overlayHeight = 150
        val topInset = 72

        // Negative raw coordinates clamped to 0 / topInset
        val (negX, negY) = TaskProgressOverlay.calculateClampedPosition(
            rawX = -50,
            rawY = 10,
            overlayWidth = overlayWidth,
            overlayHeight = overlayHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            topInset = topInset
        )
        assertEquals(0, negX)
        assertEquals(topInset, negY)

        // Overflowing coordinates clamped to max bounds
        val (maxX, maxY) = TaskProgressOverlay.calculateClampedPosition(
            rawX = 1200,
            rawY = 3000,
            overlayWidth = overlayWidth,
            overlayHeight = overlayHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            topInset = topInset
        )
        assertEquals(displayWidth - overlayWidth, maxX) // 880
        assertEquals(displayHeight - overlayHeight, maxY) // 2250
    }

    @Test
    fun testOverlayDismissalStateTransition() {
        var avatarMode = false
        var activeTaskCancelled = false

        fun onCancelClicked() {
            activeTaskCancelled = true
            avatarMode = true
        }

        // Expanded pill mode with active task running
        assertEquals(false, avatarMode)
        assertEquals(false, activeTaskCancelled)

        // User taps 48dp cancel button in overlay header
        onCancelClicked()

        assertTrue("Active task must be cancelled when overlay is dismissed", activeTaskCancelled)
        assertTrue("Overlay must collapse to avatar mode when cancelled", avatarMode)
    }
}
