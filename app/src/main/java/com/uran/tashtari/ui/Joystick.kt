package com.uran.tashtari.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

const val JOYSTICK_DEAD_ZONE = 0.10f
private const val THUMB_FRACTION = 0.28f   // thumb radius as a fraction of the base radius

// Removes the dead zone and rescales so full deflection still reaches +/-1.0
private fun applyDeadZone(v: Float): Float {
    val a = abs(v)
    if (a <= JOYSTICK_DEAD_ZONE) return 0f
    return sign(v) * ((a - JOYSTICK_DEAD_ZONE) / (1f - JOYSTICK_DEAD_ZONE)).coerceIn(0f, 1f)
}

/**
 * Circular spring-loaded joystick.
 * onChange(x, y): both -1..1 after dead zone, x right = +, y up = +.
 * onRelease(): finger lifted, gesture cancelled, or the stick became disabled. Always zero everything here.
 * Two sticks can be used at once because each one handles only its own pointer.
 */
@Composable
fun VirtualJoystick(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (x: Float, y: Float) -> Unit,
    onRelease: () -> Unit
) {
    var active by remember { mutableStateOf(false) }
    var nx by remember { mutableFloatStateOf(0f) }
    var ny by remember { mutableFloatStateOf(0f) }

    val onChangeLatest by rememberUpdatedState(onChange)
    val onReleaseLatest by rememberUpdatedState(onRelease)

    // The thumb follows the finger exactly, then springs back visually.
    // The VALUES sent to the drone snap to zero immediately on release.
    val shownX by animateFloatAsState(
        targetValue = if (active) nx else 0f,
        animationSpec = if (active) snap<Float>() else spring<Float>(),
        label = "joystickX"
    )
    val shownY by animateFloatAsState(
        targetValue = if (active) ny else 0f,
        animationSpec = if (active) snap<Float>() else spring<Float>(),
        label = "joystickY"
    )

    val accent = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val travel = (w / 2f) * (1f - THUMB_FRACTION)
                val center = Offset(w / 2f, size.height / 2f)

                fun update(p: Offset) {
                    var dx = (p.x - center.x) / travel
                    var dy = (center.y - p.y) / travel      // screen Y grows downward, so flip
                    val m = hypot(dx, dy)
                    if (m > 1f) { dx /= m; dy /= m }        // keep the thumb inside the circle
                    nx = dx
                    ny = dy
                    onChangeLatest(applyDeadZone(dx), applyDeadZone(dy))
                }

                active = true
                update(down.position)
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        update(change.position)
                        change.consume()
                    }
                } finally {
                    // Runs on release, on cancellation and when `enabled` turns false
                    active = false
                    nx = 0f
                    ny = 0f
                    onReleaseLatest()
                }
            }
        }
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.width / 2f
        val thumbR = r * THUMB_FRACTION
        val travel = r - thumbR

        // base, ring, dead-zone circle and crosshair
        drawCircle(color = Color(0xFF0F1A2E), radius = r, center = c)
        drawCircle(color = LineColor, radius = r, center = c, style = Stroke(width = 2.dp.toPx()))
        drawCircle(
            color = LineColor.copy(alpha = 0.7f),
            radius = travel * JOYSTICK_DEAD_ZONE,
            center = c,
            style = Stroke(width = 1.dp.toPx())
        )
        drawLine(LineColor, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1.dp.toPx())
        drawLine(LineColor, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1.dp.toPx())

        // thumb
        val t = Offset(c.x + shownX * travel, c.y - shownY * travel)
        if (enabled) {
            drawLine(accent.copy(alpha = 0.35f), c, t, strokeWidth = 3.dp.toPx())
        }
        drawCircle(
            color = when {
                !enabled -> Color(0xFF1A2236)
                active -> accent
                else -> Color(0xFF1F2A44)
            },
            radius = thumbR,
            center = t
        )
        drawCircle(
            color = if (enabled) accent else DisabledGrey,
            radius = thumbR,
            center = t,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}