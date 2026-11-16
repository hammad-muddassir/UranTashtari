package com.uran.tashtari.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uran.tashtari.camera.CameraPreview
import com.uran.tashtari.camera.DotVisionPipeline
import com.uran.tashtari.camera.TrackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

const val DEFAULT_CAMERA_URL = "http://192.168.4.1/stream"

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    var urlInput by remember { mutableStateOf(DEFAULT_CAMERA_URL) }
    var activeUrl by remember { mutableStateOf(DEFAULT_CAMERA_URL) }
    val pipeline = remember(activeUrl) { DotVisionPipeline() }
    val resultFlow = remember { MutableStateFlow<DotVisionPipeline.Result?>(null) }
    val errorFlow = remember { MutableStateFlow<String?>(null) }
    val visionResult by resultFlow.collectAsState()
    val visionError by errorFlow.collectAsState()

    LaunchedEffect(pipeline) {
        resultFlow.value = null
        errorFlow.value = null
        while (true) {
            val latest = withContext(Dispatchers.Default) { pipeline.tick() }
            if (latest != null) resultFlow.value = latest
            delay(100)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("Camera - Wi-Fi MJPEG")

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
                .border(1.dp, LineColor, RoundedCornerShape(20.dp))
        ) {
            // Leaving this tab disposes the preview, which closes the stream and frees the camera
            CameraPreview(
                url = activeUrl,
                modifier = Modifier.fillMaxSize(),
                onFrame = { bitmap ->
                    try {
                        val result = pipeline.process(bitmap)
                        resultFlow.value = result
                        errorFlow.value = null
                    } catch (error: Exception) {
                        errorFlow.value = error.message ?: "Vision processing failed"
                    }
                },
                onTap = { x, y ->
                    pipeline.selectAt(x, y)?.let { selected ->
                        resultFlow.value = selected
                        errorFlow.value = null
                    }
                }
            )
            DotTrackingOverlay(visionResult, Modifier.fillMaxSize())
        }

        Panel {
            SectionLabel("BLACK DOT TRACKING")
            Spacer(Modifier.height(8.dp))
            val result = visionResult
            val track = result?.track
            if (visionError != null) {
                Text("Vision error: $visionError", color = Color(0xFFFF8A80), fontSize = 13.sp)
            } else if (track == null) {
                Text("Waiting for camera frames...", color = Muted, fontSize = 13.sp)
            } else {
                val stateColor = when (track.state) {
                    TrackState.TARGET -> Color(0xFF4CAF50)
                    TrackState.UNCERTAIN -> Color(0xFFFFB74D)
                    TrackState.LOST -> Color(0xFFFF5252)
                    TrackState.NO_TARGET -> Color(0xFFB0BEC5)
                }
                Text("${track.state} | confidence %.2f | candidates ${track.candidates} | ${result.processingMs} ms"
                    .format(track.confidence), color = stateColor, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                if (track.ex.isFinite() && track.ey.isFinite()) {
                    Text("Normalized offset: X %.2f, Y %.2f | detection age ${track.ageMs} ms"
                        .format(track.ex, track.ey), color = Color.White, fontSize = 13.sp)
                } else {
                    Text("No selected target", color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                val guidance = when {
                    track.state == TrackState.LOST -> "Target lost. Tap a visible dot to select again."
                    track.state == TrackState.NO_TARGET && track.candidates > 1 ->
                        "Multiple dots found. Tap the user's dot to select it."
                    track.state == TrackState.NO_TARGET -> "Searching for a black dot surrounded by a white cap."
                    else -> "Tap a different dot only when you intend to change the selected target."
                }
                Text(guidance, color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Vision preview only: tracking data is not connected to flight commands.",
                color = Muted,
                fontSize = 12.sp
            )
        }

        Panel {
            SectionLabel("STREAM URL")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { activeUrl = urlInput.trim() }) { Text("APPLY / RETRY") }
                Button(onClick = { urlInput = DEFAULT_CAMERA_URL; activeUrl = DEFAULT_CAMERA_URL }) {
                    Text("DEFAULT")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "The camera serves one viewer at a time. If it shows 0.0 FPS: close every other " +
                        "viewer, power-cycle the camera, then press APPLY / RETRY. Only 192.168.4.1 is " +
                        "allowed for plain HTTP in network_security_config.xml.",
                color = Muted, fontSize = 12.sp
            )
        }
    }
}
