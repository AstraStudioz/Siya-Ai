package com.jarvis.assistant.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

object CameraFrameProcessor {

    const val MAX_FRAME_DIMENSION = 768
    const val THUMBNAIL_SIZE = 32
    const val FORCE_SEND_INTERVAL_MS = 3000L
    const val DIFFERENCE_THRESHOLD = 15.0f

    /**
     * Calculates proportional downscaled dimensions maintaining aspect ratio,
     * such that the longest side does not exceed [maxDimension].
     */
    fun calculateDownscaledDimensions(
        width: Int,
        height: Int,
        maxDimension: Int = MAX_FRAME_DIMENSION
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val maxSide = maxOf(width, height)
        if (maxSide <= maxDimension) return width to height

        val scale = maxDimension.toFloat() / maxSide
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }

    /**
     * Generates a small 32x32 grayscale thumbnail array for efficient frame difference comparison.
     */
    fun computeGrayscaleThumbnail(bitmap: Bitmap, size: Int = THUMBNAIL_SIZE): IntArray {
        val scaled = if (bitmap.width == size && bitmap.height == size) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, size, size, false)
        }

        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val grayscale = IntArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            grayscale[i] = ((0.299f * r) + (0.587f * g) + (0.114f * b)).toInt()
        }
        return grayscale
    }

    /**
     * Checks if current frame thumbnail is sufficiently different from previous thumbnail.
     */
    fun isFrameDifferent(
        currentThumb: IntArray?,
        lastThumb: IntArray?,
        threshold: Float = DIFFERENCE_THRESHOLD
    ): Boolean {
        if (currentThumb == null) return false
        if (lastThumb == null) return true
        if (currentThumb.size != lastThumb.size || currentThumb.isEmpty()) return true

        var totalDiff = 0L
        for (i in currentThumb.indices) {
            totalDiff += abs(currentThumb[i] - lastThumb[i])
        }
        val avgDiff = totalDiff.toFloat() / currentThumb.size
        return avgDiff > threshold
    }

    const val DEFAULT_FRAME_THROTTLE_MS = 1000L

    /**
     * Determines whether a frame should be streamed to Gemini Live:
     * - Returns true if the frame has changed visually.
     * - Returns true if 3000ms has elapsed since the last sent frame, even if visually identical.
     */
    fun shouldSendFrame(
        isDifferent: Boolean,
        lastSentTimestampMs: Long,
        currentTimestampMs: Long,
        forceSendIntervalMs: Long = FORCE_SEND_INTERVAL_MS
    ): Boolean {
        if (lastSentTimestampMs <= 0L) return true
        if (isDifferent) return true
        val elapsed = currentTimestampMs - lastSentTimestampMs
        return elapsed >= forceSendIntervalMs
    }
}

/**
 * Throttles frame sampling to enforce a maximum frame rate (e.g. 1 frame every 1000ms - 1500ms).
 */
class FrameRateThrottler(
    val intervalMs: Long = CameraFrameProcessor.DEFAULT_FRAME_THROTTLE_MS
) {
    private var lastEmittedTimestampMs: Long = 0L

    fun shouldProcess(currentTimeMs: Long): Boolean {
        if (lastEmittedTimestampMs <= 0L || (currentTimeMs - lastEmittedTimestampMs) >= intervalMs) {
            lastEmittedTimestampMs = currentTimeMs
            return true
        }
        return false
    }

    fun reset() {
        lastEmittedTimestampMs = 0L
    }

    fun getLastEmittedTimestamp(): Long = lastEmittedTimestampMs
}

/**
 * Conflated frame queue (capacity = 1, latest wins).
 * If a frame arrives while an existing frame is waiting in the queue,
 * the older frame is dropped and passed to [onDropped] for recycling.
 */
class ConflatedFrameQueue<T>(
    private val onDropped: ((T) -> Unit)? = null
) {
    private val reference = java.util.concurrent.atomic.AtomicReference<T?>(null)

    fun offer(item: T): Boolean {
        val old = reference.getAndSet(item)
        if (old != null && old !== item) {
            try {
                onDropped?.invoke(old)
            } catch (_: Throwable) {}
        }
        return true
    }

    fun poll(): T? {
        return reference.getAndSet(null)
    }

    fun peek(): T? = reference.get()

    fun isEmpty(): Boolean = reference.get() == null

    fun clear() {
        val old = reference.getAndSet(null)
        if (old != null) {
            try {
                onDropped?.invoke(old)
            } catch (_: Throwable) {}
        }
    }
}

/**
 * Reusable pre-allocated ByteArrayOutputStream to eliminate GC pressure during frame encoding.
 */
class ReusableByteArrayOutputStream(initialCapacity: Int = 128 * 1024) : java.io.ByteArrayOutputStream(initialCapacity) {
    @Synchronized
    fun <R> useStream(block: (java.io.ByteArrayOutputStream) -> R): R {
        reset()
        return block(this)
    }
}

