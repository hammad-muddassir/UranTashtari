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
import kotlin.math.max
import kotlin.math.roundToInt

private const val AXIS_MAX = 20   // BLE v0.1 limit: -20..+20

@Composable
fun ControlScreen(state: UranAppState, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val link by state.sender.state.collectAsState()
    val manualEnabled = link.status == LinkStatus.READY && !state.stopped
    val items = healthItems(state, link)

    Column(modifier.fillMaxSize()) {

        // ===== Pinned: always visible, never scrolls away =====
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ScreenHeader("Simulation only - v0.2 - no motors") {
                if (state.stopped) StatusPill("UI LOCKED", Danger)
                else StatusPill(items[0].value, items[0].health.tint())
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { state.disarm() },
                    modifier = Modifier.weight(1f).height(60.dp),
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
                    modifier = Modifier.weight(1f).height(60.dp),
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
            BatteryBanner(state)
        }

        // ===== Scrollable area =====
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ---------- Connection (compact) ----------
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("COMM LINK")
                        Text(link.message, color = Color.White, fontSize = 13.sp)
                        Text(
                            "Last reply: ${state.lastReply}",
                            color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            if (link.status == LinkStatus.IDLE) onConnect() else state.sender.disconnect()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (link.status == LinkStatus.IDLE) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary,
                            contentColor = if (link.status == LinkStatus.IDLE) Color(0xFF04121A) else Color.White
                        )
                    ) {
                        Text(
                            when (link.status) {
                                LinkStatus.IDLE -> "CONNECT"
                                LinkStatus.READY -> "DISCONNECT"
                                else -> "CANCEL"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    "Device: ${UranProtocol.DEVICE_NAME}",
                    color = DisabledGrey, fontSize = 10.sp
                )
            }

            // ---------- Health (real data or UNKNOWN) ----------
            items.chunked(3).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { HealthPill(it.label, it.value, it.health, Modifier.weight(1f)) }
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
            BatteryRow(state)
            // ---------- Joysticks ----------
            Panel {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("FLIGHT STICKS")
                    Text(
                        if (manualEnabled) "LIVE" else "LOCKED",
                        color = if (manualEnabled) Ok else Muted,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {

                    // Left: throttle (up) + yaw (left/right)
                    StickBlock(
                        title = "THROTTLE / YAW",
                        readout = "THR %3d%%\nYAW %+3d".format(state.throttleSelected, state.yaw),
                        enabled = manualEnabled,
                        modifier = Modifier.weight(1f),
                        onChange = { x, y ->
                            state.yaw = (x * AXIS_MAX).roundToInt()
                            // Up only: pushing down stays at 0, throttle is never negative.
                            // UI only. This value is never put into a BLE packet.
                            state.throttleSelected = (max(0f, y) * 100f).roundToInt()
                        },
                        onRelease = {
                            state.yaw = 0
                            state.throttleSelected = 0
                        }
                    )

                    // Right: pitch (up/down) + roll (left/right)
                    StickBlock(
                        title = "PITCH / ROLL",
                        readout = "PITCH %+3d\nROLL  %+3d".format(state.pitch, state.roll),
                        enabled = manualEnabled,
                        modifier = Modifier.weight(1f),
                        onChange = { x, y ->
                            state.roll = (x * AXIS_MAX).roundToInt()
                            state.pitch = (y * AXIS_MAX).roundToInt()
                        },
                        onRelease = {
                            state.roll = 0
                            state.pitch = 0
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Roll, pitch and yaw return to 0 on release. Throttle is shown on screen only and is NOT transmitted. " +
                            "Directions (right = roll +, up = pitch +, right = yaw +) are placeholders until Usman confirms the sign convention.",
                    color = DisabledGrey, fontSize = 11.sp
                )
            }

            // ---------- Throttle: four separate meanings, never mixed ----------
            Panel {
                SectionLabel("THROTTLE STATE")
                Spacer(Modifier.height(6.dp))
                ThrottleRow("Requested (stick, UI only)", "${state.throttleSelected} %")
                ThrottleRow("Transmitted to COMM", "NOT SENT (packet field is 0)", Warn)
                ThrottleRow("Flight-confirmed (simulation)", "UNKNOWN", Muted)
                ThrottleRow("Physical motor throttle", "DISABLED", Danger)
            }

            // ---------- Simulation controls: disabled, nothing is sent ----------
            Panel {
                SectionLabel("SIMULATION")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PendingAction("LEVEL CALIBRATE", "Needs COMM relay and Flight ACK")
                    PendingAction("START SIMULATION", "Enabled only when Flight confirms MPU, calibration, fresh RC and no ESTOP")
                    PendingAction("STOP SIMULATION", "Waiting for a verified Flight stop command")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StickBlock(
    title: String,
    readout: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Float, Float) -> Unit,
    onRelease: () -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title, color = Muted, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(6.dp))
        VirtualJoystick(
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            onChange = onChange,
            onRelease = onRelease
        )
        Spacer(Modifier.height(6.dp))
        Text(
            readout,
            color = if (enabled) MaterialTheme.colorScheme.primary else Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ThrottleRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}