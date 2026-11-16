package com.uran.tashtari.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DotVisionTest {
    private val w = 240
    private val h = 180

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun frame(bg: Int) = IntArray(w * h) { gray(bg) }

    private fun disc(px: IntArray, cx: Int, cy: Int, r: Int, v: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) px[y * w + x] = gray(v)
        }
    }

    @Test
    fun findsBlackDotOnWhiteCap() {
        val px = frame(110)              // mid-gray floor
        disc(px, 120, 90, 45, 235)       // white cap
        disc(px, 130, 85, 7, 20)         // black dot
        val c = DotDetector().detect(px, w, h)
        assertTrue("expected a candidate", c.isNotEmpty())
        assertTrue(abs(c[0].cx - 130.5f / w) < 0.01f)
        assertTrue(abs(c[0].cy - 85.5f / h) < 0.01f)
    }

    @Test
    fun ignoresBlackDotWithoutWhiteSurround() {
        val px = frame(110)
        disc(px, 130, 85, 7, 20)
        assertTrue(DotDetector().detect(px, w, h).isEmpty())
    }

    @Test
    fun ignoresLargeDarkBlobEvenOnWhite() {
        val px = frame(235)
        disc(px, 120, 90, 40, 20)        // way larger than a cap dot
        assertTrue(DotDetector().detect(px, w, h).isEmpty())
    }

    private fun cand(x: Float, y: Float) = DotCandidate(x, y, 0.03f, 0.9f, 0.9f, 0.001f)

    @Test
    fun trackerNeedsConfirmationThenGoesLost() {
        val t = DotTracker()
        assertEquals(TrackState.NO_TARGET, t.update(listOf(cand(0.5f, 0.5f)), 0).state)
        assertEquals(TrackState.NO_TARGET, t.update(listOf(cand(0.5f, 0.5f)), 100).state)
        assertEquals(TrackState.TARGET, t.update(listOf(cand(0.5f, 0.5f)), 200).state)
        assertEquals(TrackState.UNCERTAIN, t.tick(200 + 400).state)
        assertEquals(TrackState.LOST, t.tick(200 + 900).state)
    }

    @Test
    fun trackerDoesNotSilentlySwitchToFarCandidate() {
        val t = DotTracker()
        for (i in 0..2) t.update(listOf(cand(0.5f, 0.5f)), i * 100L)
        val out = t.update(listOf(cand(0.95f, 0.95f)), 300)
        assertEquals(TrackState.TARGET, out.state)
        assertTrue(abs(out.cx - 0.5f) < 0.001f)
    }

    @Test
    fun trackerRequiresSelectionWhenMultipleDotsAreVisible() {
        val t = DotTracker()
        val a = cand(0.25f, 0.5f)
        val b = cand(0.75f, 0.5f)
        repeat(5) { t.update(listOf(a, b), it * 100L) }
        assertEquals(TrackState.NO_TARGET, t.tick(500).state)
        assertEquals(TrackState.TARGET, t.select(b, 600).state)
        assertTrue(abs(t.tick(600).cx - 0.75f) < 0.001f)
    }

    @Test
    fun trackerDoesNotAutoAcquireANewDotAfterTargetIsLost() {
        val t = DotTracker()
        val initial = cand(0.5f, 0.5f)
        repeat(3) { t.update(listOf(initial), it * 100L) }
        assertEquals(TrackState.TARGET, t.tick(200).state)
        assertEquals(TrackState.LOST, t.tick(1100).state)
        repeat(5) { t.update(listOf(cand(0.95f, 0.95f)), 1200 + it * 100L) }
        assertEquals(TrackState.LOST, t.tick(1700).state)
    }
}
