package com.uran.tashtari.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DiagnosticsScreen(state: UranAppState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val link by state.sender.state.collectAsState()
    val items = healthItems(state, link)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("Diagnostics")

        Panel {
            SectionLabel("SYSTEM HEALTH")
            Spacer(Modifier.height(6.dp))
            items.forEach { HealthRow(it) }
        }

        Panel {
            SectionLabel("FLIGHT ATTITUDE")
            Spacer(Modifier.height(8.dp))
            StatRow("Roll", "UNAVAILABLE")
            StatRow("Pitch", "UNAVAILABLE")
            StatRow("Yaw rate (GyroZ)", "UNAVAILABLE")
            Text(
                "No Flight telemetry reaches the app yet. Values appear in Stage 5 and turn STALE when they stop updating.",
                color = DisabledGrey, fontSize = 11.sp
            )
        }

        Panel {
            SectionLabel("VIRTUAL MOTORS (simulation only)")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MotorBar("FL", null)
                MotorBar("FR", null)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MotorBar("RL", null)
                MotorBar("RR", null)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "N/A until Flight sends real values. Never physical motor output.",
                color = DisabledGrey, fontSize = 11.sp
            )
        }

        Panel {
            SectionLabel("COMM LINK STATS")
            Spacer(Modifier.height(8.dp))
            StatRow("Joystick packets sent", "${state.rcSent}")
            StatRow("COMM ACKs received", "${state.rcAcks}")
            StatRow(
                "Last COMM ACK age",
                if (state.rcAcks > 0) "${state.now - state.lastRcAckAt} ms" else "-"
            )
            StatRow(
                "ACK round trip avg / max",
                if (state.rcAcks > 0) "%.0f / %d ms".format(state.rttAvgMs, state.rttMaxMs) else "-"
            )
            StatRow("Last COMM reply", state.lastReply)
            Text(
                "A COMM ACK only proves COMM received the packet. It does not prove Flight acted on it.",
                color = DisabledGrey, fontSize = 11.sp
            )
        }

        Panel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("EVENT LOG (last 100)")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Copy", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("URAN TASHTRI log", state.logText()))
                            Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                        })
                    Text("Share", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "URAN TASHTRI log")
                                putExtra(Intent.EXTRA_TEXT, state.logText())
                            }
                            context.startActivity(Intent.createChooser(send, "Share log"))
                        })
                    Text("Clear", color = Muted, fontSize = 13.sp,
                        modifier = Modifier.clickable { state.clearLog() })
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF070C16))
                    .padding(10.dp)
            ) {
                if (state.log.isEmpty()) {
                    Text("No events yet", color = DisabledGrey, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                state.log.take(40).forEach {
                    Text(
                        it.format(),
                        color = when (it.tag) { "ERR" -> Danger; "UI" -> Warn; else -> Ok },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HealthRow(item: HealthItem) {
    val c = item.health.tint()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(c))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(item.detail, color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(item.value, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MotorBar(label: String, value: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .height(80.dp).width(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0B1220)),
            contentAlignment = Alignment.BottomCenter
        ) {
            if (value != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(value.coerceIn(0, 100) / 100f)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value?.toString() ?: "N/A", color = Muted, fontSize = 11.sp)
    }
}