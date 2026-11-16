package com.uran.tashtari.camera

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Frame in -> annotated frame + TrackOutput out.
 * VISION ONLY: this class has no reference to BleUart and sends nothing anywhere.
 *
 * Safe to call from the MJPEG reader thread (it does not touch views).
 */
class DotVisionPipeline(
    private val workWidth: Int = 240,
    private val detector: DotDetector = DotDetector(),
    val tracker: DotTracker = DotTracker()
) {
    data class Result(
        val track: TrackOutput,
        val candidates: List<DotCandidate>,
        val processingMs: Long,
        internal val frameTimeMs: Long,
        val frameWidth: Int,
        val frameHeight: Int
    )

    @Volatile private var latestResult: Result? = null

    init {
        require(workWidth >= 8) { "workWidth must be at least 8 pixels" }
    }

    @Synchronized
    fun process(frame: Bitmap, nowMs: Long = SystemClock.elapsedRealtime()): Result {
        val t0 = SystemClock.elapsedRealtime()
        val ww = workWidth
        val wh = max(8, (frame.height * ww.toFloat() / frame.width).roundToInt())
        val small = Bitmap.createScaledBitmap(frame, ww, wh, true)
        val px = IntArray(ww * wh)
        small.getPixels(px, 0, ww, 0, 0, ww, wh)
        if (small !== frame) small.recycle()

        val candidates = detector.detect(px, ww, wh)
        val track = tracker.update(candidates, nowMs)
        return Result(
            track = track,
            candidates = candidates,
            processingMs = SystemClock.elapsedRealtime() - t0,
            frameTimeMs = nowMs,
            frameWidth = frame.width,
            frameHeight = frame.height
        )
            .also { latestResult = it }
    }

    /** Lock onto the detected dot nearest a tap in normalized image coordinates. */
    @Synchronized
    fun selectAt(x: Float, y: Float, nowMs: Long = SystemClock.elapsedRealtime()): Result? {
        if (x !in 0f..1f || y !in 0f..1f) return null
        val current = latestResult ?: return null
        if (nowMs < current.frameTimeMs || nowMs - current.frameTimeMs > 300L) return null
        val candidate = current.candidates.minByOrNull { distance(it.cx, it.cy, x, y) } ?: return null
        if (distance(candidate.cx, candidate.cy, x, y) > max(0.035f, candidate.radius * 3f)) return null
        return refresh(current, tracker.select(candidate, nowMs))
    }

    /** Update target freshness even when the MJPEG stream stops delivering frames. */
    @Synchronized
    fun tick(nowMs: Long = SystemClock.elapsedRealtime()): Result? {
        val current = latestResult ?: return null
        return refresh(current, tracker.tick(nowMs))
    }

    @Synchronized
    fun reset() {
        tracker.reset()
        latestResult = null
    }

    private fun refresh(current: Result, track: TrackOutput): Result {
        return current.copy(track = track).also { latestResult = it }
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}
