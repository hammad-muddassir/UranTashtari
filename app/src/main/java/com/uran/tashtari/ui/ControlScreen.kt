package com.uran.tashtari.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uran.tashtari.network.LinkStatus
import java.util.Locale
import kotlin.math.roundToInt

private const val AXIS_MAX = 20

@Composable
fun ControlScreen(
    state: UranAppState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val link by state.sender.state.collectAsState()
    val connected = link.status == LinkStatus.READY
    val manual = connected && state.flightTestSelected
    val armed = manual && state.flightArmed && !state.stopped
    val canPrepare = manual && !state.stopped && !armed && !state.flightArmPending
    val accent = MaterialTheme.colorScheme.primary

    val flightStatus = when {
        state.stopped -> "E-STOP LOCKED"
        !connected -> "DISCONNECTED"
        state.flightArmPending -> "ARMING"
        armed -> "ARMED"
        state.liveStatus == "READY" && manual -> "READY"
        state.liveStatus == "FAULT" -> "FAULT"
        state.liveStatus == "CFG" -> "CHECK CONFIG"
        else -> "DISARMED"
    }
    val statusColor = when {
        state.stopped || state.liveStatus == "FAULT" -> Danger
        armed || state.flightArmPending || state.liveStatus == "CFG" -> Warn
        flightStatus == "READY" -> Ok
        else -> Muted
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        ScreenHeader("Flight controller") {
            StatusPill(flightStatus, statusColor)
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("Drone link", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    link.message,
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = {
                    if (link.status == LinkStatus.IDLE) onConnect() else state.sender.disconnect()
                },
                enabled = !armed && !state.flightArmPending
            ) {
                Text(
                    when (link.status) {
                        LinkStatus.IDLE -> "Connect"
                        LinkStatus.READY -> "Disconnect"
                        else -> "Cancel"
                    }
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { state.disarm() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Warn,
                    contentColor = Color(0xFF1A1200)
                )
            ) { Text("DISARM", fontWeight = FontWeight.Bold) }

            Button(
                onClick = { state.estop() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB91C1C),
                    contentColor = Color.White
                )
            ) { Text("E-STOP", fontWeight = FontWeight.Bold) }
        }

        FlightBatteryStrip(state, connected)

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(PanelColor).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f).height(36.dp)
                        .clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Manual", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) {
                    Text("Auto (in development)", color = Muted, fontSize = 11.sp, maxLines = 1)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    enabled = canPrepare,
                    onClick = { state.sender.sendCommand("LEVEL") }
                ) { Text("Calibrate level", maxLines = 1) }

                Button(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    enabled = if (state.stopped) connected else canPrepare && state.liveStatus == "READY",
                    onClick = {
                        if (state.stopped) state.resetUi() else state.armFlight()
                    }
                ) { Text(if (state.stopped) "Reset E-STOP" else "Arm flight", maxLines = 1) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StickBlock(
                    title = "YAW",
                    readout = "%+d".format(state.yaw),
                    enabled = armed,
                    modifier = Modifier.weight(1f),
                    onChange = { x, _ -> state.yaw = (x * AXIS_MAX).roundToInt() },
                    onRelease = { state.yaw = 0 }
                )
                StickBlock(
                    title = "PITCH / ROLL",
                    readout = "%+d / %+d".format(state.pitch, state.roll),
                    enabled = armed,
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

            Panel {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Throttle ${state.throttleSelected}%",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "BLE sent ${state.throttleTransmitted}%",
                        color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
                Slider(
                    value = state.throttleSelected.toFloat(),
                    onValueChange = { state.throttleSelected = it.roundToInt() },
                    valueRange = 0f..100f,
                    enabled = armed
                )
            }

            Text(
                when {
                    state.stopped -> "Reset the Flight ESP32 after E-STOP, then wait for fresh telemetry before tapping Reset E-STOP."
                    !connected -> "Connect to the drone to enable flight controls."
                    state.flightArmPending -> "Waiting for arm confirmation from Flight."
                    armed -> "Throttle holds when released. Tabs unlock after disarm."
                    state.liveStatus == "READY" -> "Keep throttle at zero and release both sticks before arming."
                    state.liveStatus == "CFG" -> "Verify the aircraft configuration on the FLIGHT ESP32."
                    state.liveStatus == "FAULT" -> "Flight stopped. Check Diagnostics for the fault."
                    else -> "Keep the frame level and check the physical key and Flight status."
                },
                color = if (state.stopped || state.liveStatus == "FAULT") Danger else Muted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun FlightBatteryStrip(state: UranAppState, connected: Boolean) {
    val battery = state.battery
    val volts = battery.rawVolts?.takeIf { it.isFinite() }
    val hasData = battery.lastUpdateMs != 0L
    val ageMs = (state.now - battery.lastUpdateMs).coerceAtLeast(0L)
    val stale = hasData && ageMs >= 1500L
    val live = connected && hasData && !stale && battery.sensorValid && volts != null
    val status = when {
        !connected -> "DISCONNECTED"
        !hasData -> "NO DATA"
        !battery.sensorValid || volts == null -> "INVALID"
        stale -> "STALE"
        battery.level == BatteryLevel.CRITICAL -> "CRITICAL"
        battery.level == BatteryLevel.LOW -> "LOW"
        else -> "LIVE"
    }
    val statusColor = when (status) {
        "LIVE" -> Ok
        "CRITICAL" -> Danger
        "LOW", "INVALID", "STALE" -> Warn
        else -> Muted
    }
    val voltageText = if (live) String.format(Locale.US, "%.3f V", volts) else "-- V"
    val minimumText = battery.minVolts?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "App min %.3f V", it)
    } ?: "App min -- V"
    val telemetryText = if (connected && hasData) {
        String.format(Locale.US, "%s · %.1f s ago", status, ageMs / 1000.0)
    } else {
        status
    }
    val shape = RoundedCornerShape(14.dp)

    // Fixed height keeps the stick positions steady as battery telemetry arrives.
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(shape)
            .background(PanelColor).border(1.dp, LineColor, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel("BATTERY")
            Text(
                voltageText,
                color = if (live) Color.White else Muted,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(telemetryText, color = statusColor, fontSize = 10.sp, maxLines = 1)
            Text(minimumText, color = Muted, fontSize = 10.sp, maxLines = 1)
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
