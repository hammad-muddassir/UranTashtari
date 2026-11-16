package com.uran.tashtari.camera

import kotlin.math.sqrt

enum class TrackState { NO_TARGET, TARGET, UNCERTAIN, LOST }

/**
 * Output for DISPLAY / LOGGING only.
 * ex, ey, cx, cy, radius are NaN unless state is TARGET or UNCERTAIN.
 * Do NOT map ex/ey to RC. That needs a separately tested control law and flight-safety review.
 */
data class TrackOutput(
    val state: TrackState,
    val ex: Float,
    val ey: Float,
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val confidence: Float,
    /** Milliseconds since the target was last actually detected. */
    val ageMs: Long,
    val candidates: Int
)

/**
 * Turns per-frame candidates into a stable target:
 *  - needs [confirmFrames] consistent detections before it reports TARGET
 *  - once tracking, only accepts a unique candidate within [maxJump] of the last position
 *    (ambiguous nearby dots are ignored instead of silently switching)
 *  - goes UNCERTAIN after [uncertainAfterMs] and LOST after [lostAfterMs] without detection
 *  - call [tick] on a timer too, so staleness is reported even if frames stop arriving
 */
class DotTracker(
    private val alpha: Float = 0.6f,            // weight of the newest measurement (EMA)
    private val uncertainAfterMs: Long = 300,
    private val lostAfterMs: Long = 800,
    private val maxJump: Float = 0.30f,         // normalized frame units
    private val confirmFrames: Int = 3,
    private val minTargetConfidence: Float = 0.6f
) {
    init {
        require(alpha in 0f..1f)
        require(uncertainAfterMs >= 0 && lostAfterMs > uncertainAfterMs)
        require(maxJump > 0f)
        require(confirmFrames > 0)
        require(minTargetConfidence in 0f..1f)
    }

    private var tracking = false
    private var lost = false
    private var selectionLocked = false
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var conf = 0f
    private var lastSeenMs = 0L
    private var pendingCount = 0
    private var pendingCx = 0f
    private var pendingCy = 0f
    private var latestCandidateCount = 0

    @Synchronized
    fun update(candidates: List<DotCandidate>, nowMs: Long): TrackOutput {
        latestCandidateCount = candidates.size
        if (tracking) {
            val nearby = candidates
                .filter { dist(it.cx, it.cy, cx, cy) <= maxJump }
            val near = nearby.singleOrNull()
            if (near != null) {
                cx += alpha * (near.cx - cx)
                cy += alpha * (near.cy - cy)
                radius = near.radius
                conf = near.confidence
                lastSeenMs = nowMs
            }
        } else if (selectionLocked || lost) {
            // Never silently switch to another dot; after LOST, require an explicit tap.
            pendingCount = 0
        } else if (candidates.size == 1) {
            val best = candidates.single()
            pendingCount = if (pendingCount > 0 && dist(best.cx, best.cy, pendingCx, pendingCy) <= maxJump) {
                pendingCount + 1
            } else 1
            pendingCx = best.cx
            pendingCy = best.cy
            if (pendingCount >= confirmFrames) {
                tracking = true
                lost = false
                cx = best.cx
                cy = best.cy
                radius = best.radius
                conf = best.confidence
                lastSeenMs = nowMs
                pendingCount = 0
            }
        } else {
            // Multiple indistinguishable dots are ambiguous; wait for a tap selection.
            pendingCount = 0
        }
        return snapshot(nowMs, candidates.size)
    }

    /** Explicitly lock onto a candidate selected by the user in the preview. */
    @Synchronized
    fun select(candidate: DotCandidate, nowMs: Long): TrackOutput {
        tracking = true
        lost = false
        selectionLocked = true
        latestCandidateCount = 1
        cx = candidate.cx
        cy = candidate.cy
        radius = candidate.radius
        conf = candidate.confidence
        lastSeenMs = nowMs
        pendingCount = 0
        return snapshot(nowMs, 1)
    }

    /** Re-evaluate staleness without new data. */
    @Synchronized
    fun tick(nowMs: Long): TrackOutput = snapshot(nowMs, latestCandidateCount)

    @Synchronized
    fun reset() {
        tracking = false
        lost = false
        selectionLocked = false
        lastSeenMs = 0L
        pendingCount = 0
        latestCandidateCount = 0
    }

    private fun snapshot(nowMs: Long, n: Int): TrackOutput {
        if (tracking) {
            val age = (nowMs - lastSeenMs).coerceAtLeast(0L)
            if (age > lostAfterMs) {
                tracking = false
                lost = true
                pendingCount = 0
            } else {
                val state = if (age > uncertainAfterMs || conf < minTargetConfidence)
                    TrackState.UNCERTAIN else TrackState.TARGET
                return TrackOutput(state, cx * 2f - 1f, cy * 2f - 1f, cx, cy, radius, conf, age, n)
            }
        }
        val nan = Float.NaN
        return TrackOutput(
            if (lost) TrackState.LOST else TrackState.NO_TARGET,
            nan, nan, nan, nan, nan, 0f, Long.MAX_VALUE, n
        )
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}
