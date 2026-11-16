package com.uran.tashtari.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.uran.tashtari.camera.DotVisionPipeline
import com.uran.tashtari.camera.TrackState
import kotlin.math.max
import kotlin.math.min

/** Draws normalized dot candidates over a fit-scaled camera preview without copying each frame. */
@Composable
fun DotTrackingOverlay(result: DotVisionPipeline.Result?, modifier: Modifier = Modifier) {
    if (result == null) return

    Canvas(modifier) {
        val frameWidth = result.frameWidth.toFloat()
        val frameHeight = result.frameHeight.toFloat()
        if (frameWidth <= 0f || frameHeight <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas

        val scale = min(size.width / frameWidth, size.height / frameHeight)
        val shownWidth = frameWidth * scale
        val shownHeight = frameHeight * scale
        val left = (size.width - shownWidth) / 2f
        val top = (size.height - shownHeight) / 2f
        val center = Offset(left + shownWidth / 2f, top + shownHeight / 2f)
        val stroke = max(2f, shownWidth / 160f)

        drawLine(Color.Cyan, Offset(center.x - 12f * stroke, center.y),
            Offset(center.x + 12f * stroke, center.y), strokeWidth = stroke)
        drawLine(Color.Cyan, Offset(center.x, center.y - 12f * stroke),
            Offset(center.x, center.y + 12f * stroke), strokeWidth = stroke)

        result.candidates.forEach { candidate ->
            val point = Offset(left + candidate.cx * shownWidth, top + candidate.cy * shownHeight)
            drawCircle(
                color = Color.Yellow,
                radius = max(candidate.radius * shownWidth * 2f, 8f),
                center = point,
                style = Stroke(width = stroke * 0.6f)
            )
        }

        val track = result.track
        if ((track.state == TrackState.TARGET || track.state == TrackState.UNCERTAIN) &&
            track.cx.isFinite() && track.cy.isFinite()
        ) {
            val color = if (track.state == TrackState.TARGET) Color.Green else Color(1f, 0.65f, 0f)
            val point = Offset(left + track.cx * shownWidth, top + track.cy * shownHeight)
            drawCircle(
                color = color,
                radius = max(track.radius * shownWidth * 2.5f, 12f),
                center = point,
                style = Stroke(width = stroke * 1.5f)
            )
            drawLine(color, center, point, strokeWidth = stroke)
        }
    }
}
