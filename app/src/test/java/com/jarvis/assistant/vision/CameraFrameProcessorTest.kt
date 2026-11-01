package com.jarvis.assistant.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameProcessorTest {

    @Test
    fun testDownscaleMathLandscapeKeepsAspectRatio() {
        val (width, height) = CameraFrameProcessor.calculateDownscaledDimensions(1920, 1080, 768)
        assertEquals(768, width)
        assertEquals(432, height)
    }

    @Test
    fun testDownscaleMathPortraitKeepsAspectRatio() {
        val (width, height) = CameraFrameProcessor.calculateDownscaledDimensions(1080, 1920, 768)
        assertEquals(432, width)
        assertEquals(768, height)
    }

    @Test
    fun testDownscaleMathSmallerThanMaxIsNotScaled() {
        val (width, height) = CameraFrameProcessor.calculateDownscaledDimensions(400, 300, 768)
        assertEquals(400, width)
        assertEquals(300, height)
    }

    @Test
    fun testDownscaleMathExactMax() {
        val (width, height) = CameraFrameProcessor.calculateDownscaledDimensions(768, 768, 768)
        assertEquals(768, width)
        assertEquals(768, height)
    }

    @Test
    fun testDownscaleMathZeroAndNegative() {
        val (w1, h1) = CameraFrameProcessor.calculateDownscaledDimensions(0, 0, 768)
        assertEquals(0, w1)
        assertEquals(0, h1)

        val (w2, h2) = CameraFrameProcessor.calculateDownscaledDimensions(-10, 100, 768)
        assertEquals(0, w2)
        assertEquals(0, h2)
    }

    @Test
    fun testFrameChangeCheckIdenticalThumbnails() {
        val thumb1 = IntArray(1024) { 100 }
        val thumb2 = IntArray(1024) { 100 }

        // Identical frames should return false (not different)
        assertFalse(CameraFrameProcessor.isFrameDifferent(thumb1, thumb2, threshold = 15f))
    }

    @Test
    fun testFrameChangeCheckMinorDifferenceBelowThreshold() {
        val thumb1 = IntArray(1024) { 100 }
        val thumb2 = IntArray(1024) { 105 } // 5 diff < 15 threshold

        assertFalse(CameraFrameProcessor.isFrameDifferent(thumb1, thumb2, threshold = 15f))
    }

    @Test
    fun testFrameChangeCheckSignificantDifferenceAboveThreshold() {
        val thumb1 = IntArray(1024) { 50 }
        val thumb2 = IntArray(1024) { 150 } // 100 diff > 15 threshold

        assertTrue(CameraFrameProcessor.isFrameDifferent(thumb1, thumb2, threshold = 15f))
    }

    @Test
    fun testFrameChangeCheckNullHandling() {
        val thumb = IntArray(1024) { 50 }
        // First frame (lastThumb == null) should be treated as different so it gets sent
        assertTrue(CameraFrameProcessor.isFrameDifferent(thumb, null))
        // Null current thumb cannot be compared
        assertFalse(CameraFrameProcessor.isFrameDifferent(null, thumb))
    }

    @Test
    fun testSendAtLeastEveryThreeSecondsRule() {
        val baseTime = 10000L

        // Initial send (lastSent == 0L)
        assertTrue(CameraFrameProcessor.shouldSendFrame(isDifferent = false, lastSentTimestampMs = 0L, currentTimestampMs = baseTime))

        // Different frame always sends even if only 500ms elapsed
        assertTrue(CameraFrameProcessor.shouldSendFrame(isDifferent = true, lastSentTimestampMs = baseTime, currentTimestampMs = baseTime + 500L))

        // Identical frame does NOT send when under 3 seconds elapsed
        assertFalse(CameraFrameProcessor.shouldSendFrame(isDifferent = false, lastSentTimestampMs = baseTime, currentTimestampMs = baseTime + 1000L))
        assertFalse(CameraFrameProcessor.shouldSendFrame(isDifferent = false, lastSentTimestampMs = baseTime, currentTimestampMs = baseTime + 2999L))

        // Identical frame DOES send when exactly or more than 3 seconds elapsed (forced 3s refresh)
        assertTrue(CameraFrameProcessor.shouldSendFrame(isDifferent = false, lastSentTimestampMs = baseTime, currentTimestampMs = baseTime + 3000L))
        assertTrue(CameraFrameProcessor.shouldSendFrame(isDifferent = false, lastSentTimestampMs = baseTime, currentTimestampMs = baseTime + 4500L))
    }

    @Test
    fun testFrameRateThrottlerAllowsFirstFrameImmediately() {
        val throttler = FrameRateThrottler(intervalMs = 1000L)
        // First frame should be allowed immediately
        assertTrue(throttler.shouldProcess(currentTimeMs = 10000L))
        assertEquals(10000L, throttler.getLastEmittedTimestamp())
    }

    @Test
    fun testFrameRateThrottlerBlocksFramesUnderInterval() {
        val throttler = FrameRateThrottler(intervalMs = 1000L)
        assertTrue(throttler.shouldProcess(currentTimeMs = 10000L))

        // Frames arriving at +100ms, +500ms, +999ms should be rejected
        assertFalse(throttler.shouldProcess(currentTimeMs = 10100L))
        assertFalse(throttler.shouldProcess(currentTimeMs = 10500L))
        assertFalse(throttler.shouldProcess(currentTimeMs = 10999L))

        // Frame at exactly 1000ms elapsed should be allowed
        assertTrue(throttler.shouldProcess(currentTimeMs = 11000L))
        assertEquals(11000L, throttler.getLastEmittedTimestamp())
    }

    @Test
    fun testFrameRateThrottlerReset() {
        val throttler = FrameRateThrottler(intervalMs = 1000L)
        assertTrue(throttler.shouldProcess(currentTimeMs = 5000L))
        assertFalse(throttler.shouldProcess(currentTimeMs = 5200L))

        throttler.reset()
        assertEquals(0L, throttler.getLastEmittedTimestamp())
        // After reset, immediate frame is permitted even if timestamp is close
        assertTrue(throttler.shouldProcess(currentTimeMs = 5250L))
    }

    @Test
    fun testConflatedFrameQueueCapacityOneAndLatestWins() {
        val droppedItems = mutableListOf<String>()
        val queue = ConflatedFrameQueue<String> { dropped ->
            droppedItems.add(dropped)
        }

        assertTrue(queue.isEmpty())
        assertEquals(null, queue.poll())

        // Offer single item
        queue.offer("frame1")
        assertFalse(queue.isEmpty())
        assertEquals("frame1", queue.peek())

        // Offer second item before polling - frame1 must be dropped and frame2 retained
        queue.offer("frame2")
        assertEquals(listOf("frame1"), droppedItems)
        assertEquals("frame2", queue.peek())

        // Offer third item - frame2 must be dropped and frame3 retained
        queue.offer("frame3")
        assertEquals(listOf("frame1", "frame2"), droppedItems)

        // Polling should return latest item ("frame3")
        val polled = queue.poll()
        assertEquals("frame3", polled)
        assertTrue(queue.isEmpty())
        assertEquals(null, queue.poll())
    }

    @Test
    fun testConflatedFrameQueueClearCallsDropCallback() {
        val droppedItems = mutableListOf<String>()
        val queue = ConflatedFrameQueue<String> { dropped ->
            droppedItems.add(dropped)
        }

        queue.offer("pendingFrame")
        assertFalse(queue.isEmpty())

        queue.clear()
        assertTrue(queue.isEmpty())
        assertEquals(listOf("pendingFrame"), droppedItems)
    }
}

