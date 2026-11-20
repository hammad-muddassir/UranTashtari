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
            SectionLabel("FLIGHT STATE")
            Spacer(Modifier.height(8.dp))
            val stateLive = state.flight.stateFresh(state.now)
            StatRow("Simulation state", if (stateLive) state.flight.simState ?: "--" else "--")
            StatRow("Level calibrated", if (stateLive) yesNo(state.flight.calibrated) else "--")
            StatRow("Outputs disarmed", if (stateLive) yesNo(state.flight.disarmed) else "--")
            StatRow("Flight sees fresh RC", if (stateLive) yesNo(state.flight.rcFresh) else "--")
            StatRow(
                "Pilot link (COMM)",
                if (state.flight.commFresh(state.now)) yesNo(state.flight.pilotLink) else "--"
            )
            StatRow(
                "Last Flight telemetry",
                if (!state.flight.anyFlightEver()) "never"
                else "${state.now - state.flight.lastFlightMs()} ms ago"
            )
            StatRow("Telemetry lines ok / rejected", "${state.flight.packets} / ${state.flight.rejected}")
            if (state.flight.rejected > 0) {
                StatRow("Last rejected", state.flight.lastRejected)
            }
        }

        Panel {
            SectionLabel("FLIGHT ATTITUDE")
            Spacer(Modifier.height(8.dp))
            val attLive = state.flight.attFresh(state.now)
            StatRow("Roll", if (attLive) "%.1f deg".format(state.flight.rollDeg ?: 0f) else "--")
            StatRow("Pitch", if (attLive) "%.1f deg".format(state.flight.pitchDeg ?: 0f) else "--")
            StatRow("Yaw rate (GyroZ)", if (attLive) "%.1f deg/s".format(state.flight.gyroZ ?: 0f) else "--")
            StatRow(
                "Attitude data",
                when {
                    state.flight.lastAttMs == 0L -> "no data yet"
                    attLive -> "live"
                    else -> "STALE (${state.now - state.flight.lastAttMs} ms old)"
                }
            )
            Text(
                "Shown only while Flight telemetry is fresh. Units follow the proposed protocol: degrees and deg/s. Confirm with Flight.",
                color = DisabledGrey, fontSize = 11.sp
            )
        }

        Panel {
            SectionLabel("VIRTUAL MOTORS (simulation only)")
            Spacer(Modifier.height(10.dp))
            val m = state.flight.motors.takeIf { state.flight.motFresh(state.now) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MotorBar("FL", m?.getOrNull(0))
                MotorBar("FR", m?.getOrNull(1))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MotorBar("RL", m?.getOrNull(3))
                MotorBar("RR", m?.getOrNull(2))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    state.flight.lastMotMs == 0L -> "N/A: no motor telemetry yet."
                    m == null -> "STALE: no motor update for ${state.now - state.flight.lastMotMs} ms."
                    else -> "Live from Flight. Virtual simulation values only."
                },
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

private fun yesNo(b: Boolean?): String = when (b) {
    true -> "YES"
    false -> "NO"
    null -> "--"
}