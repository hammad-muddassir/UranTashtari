package com.uran.tashtari.camera

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun CameraPreview(
    url: String,
    modifier: Modifier = Modifier,
    onFrame: (Bitmap) -> Unit = {},
    onTap: (Float, Float) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val latestOnFrame by rememberUpdatedState(onFrame)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var message by remember { mutableStateOf("Connecting...") }
    var fps by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf("-") }
    var reconnects by remember { mutableIntStateOf(0) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val latestOnTap by rememberUpdatedState(onTap)

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
        frame = null
        while (isActive) {
            try {
                message = "Connecting..."
                var count = 0
                var windowStart = System.currentTimeMillis()
                mjpegFrames(url).conflate().collect { bmp ->
                    // Image analysis can be CPU-heavy; keep it off the Compose/UI thread.
                    withContext(Dispatchers.Default) { latestOnFrame(bmp) }
                    frame = bmp.asImageBitmap()
                    size = "${bmp.width}x${bmp.height}"
                    message = ""
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

    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(frame?.width, frame?.height, containerSize) {
                detectTapGestures { point ->
                    val image = frame ?: return@detectTapGestures
                    if (containerSize.width <= 0 || containerSize.height <= 0) return@detectTapGestures
                    val scale = min(
                        containerSize.width.toFloat() / image.width,
                        containerSize.height.toFloat() / image.height
                    )
                    val shownWidth = image.width * scale
                    val shownHeight = image.height * scale
                    val left = (containerSize.width - shownWidth) / 2f
                    val top = (containerSize.height - shownHeight) / 2f
                    val x = (point.x - left) / shownWidth
                    val y = (point.y - top) / shownHeight
                    if (x in 0f..1f && y in 0f..1f) latestOnTap(x, y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
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
