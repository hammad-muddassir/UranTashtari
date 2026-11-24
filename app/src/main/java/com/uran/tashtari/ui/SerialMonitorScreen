package com.uran.tashtari.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SerialMonitorScreen(
    state: UranAppState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var includeControl by remember { mutableStateOf(false) }
    var pausedLines by remember { mutableStateOf<List<LogEntry>?>(null) }
    val captured = pausedLines ?: state.serialLines()
    val visible = remember(captured, includeControl) {
        if (includeControl) captured else captured.filterNot {
            (it.tag == "TX" && it.text.startsWith("C,")) ||
                (it.tag == "RX" && it.text.startsWith("ACK,COMM_RC,"))
        }
    }
    BackHandler(onBack = onBack)

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Serial Monitor", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "Shows data received over Bluetooth. Captures up to $SERIAL_LINE_LIMIT recent lines while the app is open.",
            color = Muted, fontSize = 12.sp
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { includeControl = !includeControl }, modifier = Modifier.weight(1f)) {
                Text(if (includeControl) "Hide controls" else "Show controls")
            }
            OutlinedButton(onClick = {
                pausedLines = if (pausedLines == null) state.serialLines() else null
            }, modifier = Modifier.weight(1f)) {
                Text(if (pausedLines == null) "Pause view" else "Resume view")
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${visible.size} shown",
                modifier = Modifier.weight(1f),
                color = Muted,
                fontSize = 12.sp
            )
            TextButton(enabled = captured.isNotEmpty(), onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("URAN TASHTRI serial", state.trafficText()))
                Toast.makeText(context, "Serial log copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy all") }
            TextButton(enabled = captured.isNotEmpty(), onClick = {
                state.clearTraffic()
                pausedLines = null
            }) { Text("Clear") }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(PanelColor)
        ) {
            if (visible.isEmpty()) {
                Text("Waiting for Bluetooth traffic", Modifier.padding(12.dp), color = Muted)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(visible.asReversed()) { entry ->
                    Text(
                        entry.format(),
                        color = when {
                            entry.text.startsWith("ERR") || entry.text.startsWith("STOP,") -> Danger
                            entry.text.startsWith("PWM,") || entry.text.startsWith("YAW_CHECK,") -> Ok
                            else -> Muted
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
