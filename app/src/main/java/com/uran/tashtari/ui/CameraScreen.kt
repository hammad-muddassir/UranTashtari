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

const val DEFAULT_CAMERA_URL = "http://192.168.4.1/stream"

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    var urlInput by remember { mutableStateOf(DEFAULT_CAMERA_URL) }
    var activeUrl by remember { mutableStateOf(DEFAULT_CAMERA_URL) }

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
            CameraPreview(url = activeUrl, modifier = Modifier.fillMaxSize())
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
                        "allowed for plain HTTP in network_security_config.xml. Reliable reconnect " +
                        "and status arrive in Stage 6.",
                color = Muted, fontSize = 12.sp
            )
        }
    }
}