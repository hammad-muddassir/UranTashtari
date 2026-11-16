package com.urantashtri.app.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.roundToInt

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
        val annotated: Bitmap,
        val track: TrackOutput,
        val candidates: List<DotCandidate>,
        val processingMs: Long
    )

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
        val annotated = TargetOverlay.draw(frame, candidates, track)
        return Result(annotated, track, candidates, SystemClock.elapsedRealtime() - t0)
    }

    fun reset() = tracker.reset()
}

object TargetOverlay {
    fun draw(src: Bitmap, candidates: List<DotCandidate>, track: TrackOutput): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val stroke = max(2f, w / 160f)

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }

        // Frame center crosshair (where ex = ey = 0).
        p.color = Color.CYAN
        canvas.drawLine(w / 2 - 12 * stroke, h / 2, w / 2 + 12 * stroke, h / 2, p)
        canvas.drawLine(w / 2, h / 2 - 12 * stroke, w / 2, h / 2 + 12 * stroke, p)

        // All candidates, thin yellow.
        p.color = Color.YELLOW
        p.strokeWidth = stroke * 0.6f
        for (c in candidates) {
            canvas.drawCircle(c.cx * w, c.cy * h, max(c.radius * w * 2f, 8f), p)
        }

        // Selected target, thick, colored by state.
        val color = when (track.state) {
            TrackState.TARGET -> Color.GREEN
            TrackState.UNCERTAIN -> Color.rgb(255, 165, 0)
            TrackState.LOST -> Color.RED
            TrackState.NO_TARGET -> Color.GRAY
        }
        if (track.state == TrackState.TARGET || track.state == TrackState.UNCERTAIN) {
            p.color = color
            p.strokeWidth = stroke * 1.5f
            val tx = track.cx * w
            val ty = track.cy * h
            canvas.drawCircle(tx, ty, max(track.radius * w * 2.5f, 12f), p)
            canvas.drawLine(w / 2, h / 2, tx, ty, p)
        }

        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = max(14f, w / 18f)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        canvas.drawText(track.state.name, 8f, t.textSize + 4f, t)
        return bmp
    }
}
