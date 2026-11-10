package com.uran.tashtari.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uran.tashtari.network.LinkStatus
import com.uran.tashtari.network.UranProtocol

private const val STEP = 20   // v0.1 limit is -20..+20

@Composable
fun ControlScreen(state: UranAppState, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val link by state.sender.state.collectAsState()
    val manualEnabled = link.status == LinkStatus.READY && !state.stopped
    val items = healthItems(state, link)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("Simulation only - v0.2 - no motors") {
            if (state.stopped) StatusPill("UI LOCKED", Danger)
            else StatusPill(items[0].value, items[0].health.tint())
        }

        // ---------- Health: real data, or UNKNOWN ----------
        items.chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { HealthPill(it.label, it.value, it.health, Modifier.weight(1f)) }
            }
        }

        // ---------- Safety first, always at the top ----------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { state.disarm() },
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Warn, contentColor = Color(0xFF1A1200))
            ) { Text("DISARM", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    if (state.stopped) {
                        state.resetUi()
                        if (link.status == LinkStatus.IDLE) onConnect()
                    } else {
                        state.estop()
                    }
                },
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.stopped) Color(0xFF475569) else Danger,
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (state.stopped) "RESET UI / RECONNECT" else "E-STOP",
                    fontSize = if (state.stopped) 13.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (state.stopped) {
            Text(
                "E-STOP locked this app. RESET UI does not clear the Flight latch. Only a physical Flight reset does.",
                color = Danger, fontSize = 12.sp
            )
        }

        // ---------- Connection ----------
        Panel {
            SectionLabel("COMM LINK")
            Spacer(Modifier.height(8.dp))
            Text(link.message, color = Color.White, fontSize = 14.sp)
            Text("Last reply: ${state.lastReply}", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { if (link.status == LinkStatus.IDLE) onConnect() else state.sender.disconnect() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (link.status == LinkStatus.IDLE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary,
                    contentColor = if (link.status == LinkStatus.IDLE) Color(0xFF04121A) else Color.White
                )
            ) {
                Text(
                    when (link.status) {
                        LinkStatus.IDLE -> "CONNECT TO ${UranProtocol.DEVICE_NAME}"
                        LinkStatus.READY -> "DISCONNECT"
                        else -> "CANCEL"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ---------- Mode: AUTO is not implemented ----------
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PanelColor)
                .padding(4.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("MANUAL", fontWeight = FontWeight.Bold, color = Color(0xFF04121A)) }
            Box(
                Modifier.weight(1f).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("AUTO (later)", fontWeight = FontWeight.Bold, color = DisabledGrey) }
        }

        // ---------- Movement ----------
        Panel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("MOVE")
                Text(
                    "R ${state.roll}  P ${state.pitch}  Y ${state.yaw}",
                    color = if (manualEnabled) MaterialTheme.colorScheme.primary else Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HoldButton("▲", manualEnabled, Modifier.size(72.dp), { state.pitch = STEP }, { state.pitch = 0 })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoldButton("◀", manualEnabled, Modifier.size(72.dp), { state.roll = -STEP }, { state.roll = 0 })
                    Spacer(Modifier.size(72.dp))
                    HoldButton("▶", manualEnabled, Modifier.size(72.dp), { state.roll = STEP }, { state.roll = 0 })
                }
                HoldButton("▼", manualEnabled, Modifier.size(72.dp), { state.pitch = -STEP }, { state.pitch = 0 })
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HoldButton("YAW L", manualEnabled, Modifier.weight(1f).height(52.dp), { state.yaw = -STEP }, { state.yaw = 0 })
                HoldButton("YAW R", manualEnabled, Modifier.weight(1f).height(52.dp), { state.yaw = STEP }, { state.yaw = 0 })
            }
        }

        // ---------- Virtual throttle: visible but disabled until firmware exists ----------
        Panel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("VIRTUAL THROTTLE")
                StatusPill("PENDING FIRMWARE", Muted)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Selected: ${state.throttleSelected}    Flight-confirmed: -",
                color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace
            )
            Slider(
                value = state.throttleSelected.toFloat(),
                onValueChange = {},
                valueRange = 0f..100f,
                enabled = false
            )
            Text(
                "Simulation units only (0-100 proposed, pending Flight confirmation). " +
                        "Never reaches ESC outputs. Enabled in Stage 4.",
                color = DisabledGrey, fontSize = 11.sp
            )
        }

        // ---------- Simulation controls: disabled, nothing is sent ----------
        Panel {
            SectionLabel("SIMULATION")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PendingAction("LEVEL CALIBRATE", "Needs COMM relay and Flight ACK (Stages 3-4)")
                PendingAction("START SIMULATION", "Enabled only when Flight confirms MPU, calibration, fresh RC and no ESTOP")
                PendingAction("STOP SIMULATION", "Waiting for a verified Flight stop command")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}