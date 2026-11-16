package com.urantashtri.app.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detector for a BLACK DOT on a WHITE CAP, seen from above.
 *
 * VISION ONLY. Nothing in this package may call BleUart or generate RC commands.
 *
 * Pure Kotlin (no android.* imports) so it can be unit-tested on the JVM.
 * Not compiled or tested on the team's hardware yet.
 */
data class DetectorConfig(
    /** Luma (0..255) at or below this counts as "black". */
    val darkMax: Int = 70,
    /** Luma at or above this counts as "white". */
    val brightMin: Int = 150,
    /** max(R,G,B) - min(R,G,B) allowed for a pixel to count as neutral (black/white, not colored). */
    val maxChroma: Int = 70,
    /** Dot area limits as a fraction of the whole (downscaled) frame. */
    val minAreaFrac: Float = 0.0006f,
    val maxAreaFrac: Float = 0.06f,
    /** min(width,height)/max(width,height) of the blob bounding box. 1.0 = square. */
    val minAspect: Float = 0.6f,
    /** blob area / bounding-box area. A perfect disc is about 0.785. */
    val minFill: Float = 0.55f,
    /** Fraction of the ring around the dot that must be white (the cap). */
    val minWhiteRing: Float = 0.55f,
    val minConfidence: Float = 0.5f,
    val maxCandidates: Int = 5
)

/** One dot candidate. Coordinates are normalized to the frame: 0..1, origin top-left. */
data class DotCandidate(
    val cx: Float,
    val cy: Float,
    /** Equivalent radius as a fraction of frame WIDTH. */
    val radius: Float,
    val confidence: Float,
    val whiteRing: Float,
    val areaFrac: Float
) {
    /** ex = (cx - W/2) / (W/2), in [-1, 1]. Positive = right of center. */
    val ex: Float get() = cx * 2f - 1f
    /** ey = (cy - H/2) / (H/2), in [-1, 1]. Positive = below center. */
    val ey: Float get() = cy * 2f - 1f
}

class DotDetector(private val cfg: DetectorConfig = DetectorConfig()) {

    /**
     * @param argb pixels in Android ARGB_8888 packing, row-major, size >= w*h
     * @return candidates sorted by confidence, best first. Empty if no dot found.
     */
    fun detect(argb: IntArray, w: Int, h: Int): List<DotCandidate> {
        require(w >= 8 && h >= 8) { "frame too small" }
        require(argb.size >= w * h) { "pixel array too small" }
        val n = w * h

        val dark = BooleanArray(n)
        // Integral image of the "white" mask, (w+1)*(h+1).
        val ii = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowBright = 0
            for (x in 0 until w) {
                val i = y * w + x
                val p = argb[i]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val luma = (r * 77 + g * 150 + b * 29) shr 8
                val neutral = (mx - mn) <= cfg.maxChroma
                dark[i] = neutral && luma <= cfg.darkMax
                if (neutral && luma >= cfg.brightMin) rowBright++
                ii[(y + 1) * (w + 1) + (x + 1)] = ii[y * (w + 1) + (x + 1)] + rowBright
            }
        }

        val minArea = max(4, (cfg.minAreaFrac * n).toInt())
        val maxArea = (cfg.maxAreaFrac * n).toInt()
        val visited = BooleanArray(n)
        val stack = IntArray(n) // each pixel is pushed at most once
        val found = ArrayList<DotCandidate>()

        for (start in 0 until n) {
            if (!dark[start] || visited[start]) continue

            // Flood fill one dark blob (4-connected).
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) { val j = i - 1; if (dark[j] && !visited[j]) { visited[j] = true; stack[sp++] = j } }
                if (x < w - 1) { val j = i + 1; if (dark[j] && !visited[j]) { visited[j] = true; stack[sp++] = j } }
                if (y > 0) { val j = i - w; if (dark[j] && !visited[j]) { visited[j] = true; stack[sp++] = j } }
                if (y < h - 1) { val j = i + w; if (dark[j] && !visited[j]) { visited[j] = true; stack[sp++] = j } }
            }

            // Shape filters.
            if (area < minArea || area > maxArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val aspect = min(bw, bh).toFloat() / max(bw, bh)
            if (aspect < cfg.minAspect) continue
            val fill = area.toFloat() / (bw * bh)
            if (fill < cfg.minFill) continue

            // "Is it surrounded by white?" - ring between the dot bbox and a padded bbox.
            val pad = max(3, max(bw, bh) / 2)
            val ox0 = max(0, minX - pad)
            val oy0 = max(0, minY - pad)
            val ox1 = min(w, maxX + 1 + pad)
            val oy1 = min(h, maxY + 1 + pad)
            val innerArea = bw * bh
            val ringArea = (ox1 - ox0) * (oy1 - oy0) - innerArea
            if (ringArea < innerArea) continue // too clipped by the frame edge to judge
            val ringBright = rect(ii, w, ox0, oy0, ox1, oy1) - rect(ii, w, minX, minY, maxX + 1, maxY + 1)
            val whiteRing = ringBright.toFloat() / ringArea
            if (whiteRing < cfg.minWhiteRing) continue

            val circ = 1f - min(1f, abs(fill - (PI.toFloat() / 4f)) / 0.3f)
            val confidence = 0.65f * whiteRing + 0.35f * circ
            if (confidence < cfg.minConfidence) continue

            found.add(
                DotCandidate(
                    cx = ((sumX.toFloat() / area) + 0.5f) / w,
                    cy = ((sumY.toFloat() / area) + 0.5f) / h,
                    radius = sqrt(area / PI.toFloat()) / w,
                    confidence = confidence,
                    whiteRing = whiteRing,
                    areaFrac = area.toFloat() / n
                )
            )
        }

        found.sortByDescending { it.confidence }
        return if (found.size > cfg.maxCandidates) found.subList(0, cfg.maxCandidates).toList() else found
    }

    /** Sum of the white mask over [x0,x1) x [y0,y1). */
    private fun rect(ii: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val s = w + 1
        return ii[y1 * s + x1] - ii[y0 * s + x1] - ii[y1 * s + x0] + ii[y0 * s + x0]
    }
}
