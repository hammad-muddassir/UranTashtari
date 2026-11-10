package com.uran.tashtari.camera

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive

@Composable
fun CameraPreview(
    url: String,
    modifier: Modifier = Modifier,
    onFrame: (Bitmap) -> Unit = {}   // Rayyan plugs his detector in here
) {
    val context = LocalContext.current
    val latestOnFrame by rememberUpdatedState(onFrame)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var message by remember { mutableStateOf("Connecting...") }
    var fps by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf("-") }
    var reconnects by remember { mutableIntStateOf(0) }

    // Route traffic over the camera Wi-Fi even though it has no internet
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifi != null) cm.bindProcessToNetwork(wifi)
        onDispose { cm.bindProcessToNetwork(null) }
    }

    LaunchedEffect(url) {
        while (isActive) {
            try {
                message = "Connecting..."
                var count = 0
                var windowStart = System.currentTimeMillis()
                mjpegFrames(url).conflate().collect { bmp ->
                    frame = bmp.asImageBitmap()
                    size = "${bmp.width}x${bmp.height}"
                    message = ""
                    latestOnFrame(bmp)
                    count++
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 1000) {
                        fps = count * 1000f / (now - windowStart)
                        count = 0
                        windowStart = now
                    }
                }
                message = "Stream ended. Retrying..."
                reconnects++
                fps = 0f
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Stream lost: ${e.message}. Retrying..."
                reconnects++
                fps = 0f
            }
            delay(2000)
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = "Camera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        if (message.isNotEmpty()) Text(message, color = Color.White)
        Text(
            "$size | ${"%.1f".format(fps)} FPS | reconnects: $reconnects",
            color = Color(0xFF22D3EE),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}